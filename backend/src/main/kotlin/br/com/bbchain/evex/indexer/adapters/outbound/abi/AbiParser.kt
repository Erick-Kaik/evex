package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiEntryCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.AbiEntryKind
import br.com.bbchain.evex.indexer.domain.abi.AbiEventCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.CatalogedAbiEntryParam
import br.com.bbchain.evex.indexer.domain.abi.CatalogedEventInput
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethodInput
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethodOutput
import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiEntry
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiEvent
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiMethod
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.web3j.abi.EventEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Event

/**
 * Um input de evento, na ordem de declaração do ABI (mistura indexados/não-indexados) — necessário
 * pra casar as posições decodificadas de [org.web3j.abi.EventValues] de volta com o nome do
 * parâmetro Solidity original (o `Event`/`TypeReference` do Web3j não carregam nome, só tipo+flag).
 */
data class AbiEventInput(val name: String, val type: String, val indexed: Boolean)

/**
 * Descritor de um evento Solidity parseado de um ABI JSON — carrega tudo que [AbiDecodingAdapter]
 * precisa para reconhecer (`topic0`) e decodificar (`event`, `inputs`) um log contra ele.
 */
data class EventDescriptor(
    val name: String,
    val inputs: List<AbiEventInput>,
    val event: Event,
    val topic0: String,
)

/**
 * Parser dinâmico de ABI JSON (sem `web3j-gradle-plugin`, sem código gerado em compile-time — regra
 * não-negociável do projeto). Reaproveita o algoritmo do código base Anbima, documentado em
 * `backend/docs/abi-strategy-design.md` ("Reaproveitado"): `TypeReference.makeTypeReference` por
 * input -> `Event(name, typeRefs)` -> `EventEncoder.encode(event)` para o `topic0`. Só itens do ABI
 * com `"type": "event"` viram [EventDescriptor] — funções/constructor/fallback/error são ignorados.
 *
 * Também implementa [AbiEventCatalogPort] (E16 / DEC-076): `eventsOf` reaproveita o mesmo walk de
 * JSON que [parse] faz para montar os inputs, mas devolve um tipo magro ([ParsedAbiEvent]) sem
 * `Event`/`topic0` do Web3j — o catálogo de eventos só exibe, não decodifica, e não precisa calcular
 * `topic0` (o conteúdo vindo do storage já foi validado no cadastro, quando [parse] roda).
 *
 * E19/DEC-082: também implementa [AbiMethodCatalogPort] — `methodsOf` é o mirror de `eventsOf` só que
 * filtrando `"type": "function"` com `stateMutability` `view`/`pure` em vez de `"type": "event"`, e sem
 * calcular nenhum artefato de Web3j (o catálogo de métodos só exibe/monta argumentos de `eth_call`,
 * quem de fato chama a rede é [br.com.bbchain.evex.indexer.adapters.outbound.blockchain.Web3jBlockchainClient.call]).
 *
 * E24: também implementa [AbiEntryCatalogPort] — `entriesOf` é o mirror mais largo de `eventsOf`/
 * `methodsOf`, só que sem filtro nenhum de `"type"`: percorre **todo** item do ABI (função de qualquer
 * `stateMutability`, evento, erro customizado, constructor, fallback, receive) pro Explorador de ABI.
 */
@Component
class AbiParser : AbiEventCatalogPort, AbiMethodCatalogPort, AbiEntryCatalogPort {
    // Jackson 2 local, não injetado (DEC-020): Spring Boot 4.1 auto-configura um bean ObjectMapper
    // Jackson 3 (tools.jackson) por padrão, não o Jackson 2 (com.fasterxml.jackson) que este projeto
    // usa via jackson-module-kotlin/jackson-databind-nullable.
    private val objectMapper = ObjectMapper()

    fun parse(abiJson: String): List<EventDescriptor> =
        readAbiEvents(abiJson).map(::toDescriptor)

    override fun eventsOf(abiJson: String): List<ParsedAbiEvent> =
        readAbiEvents(abiJson).map { raw ->
            ParsedAbiEvent(
                name = raw.name,
                inputs = raw.inputs.map { CatalogedEventInput(it.name, it.type, it.indexed) },
            )
        }

    override fun methodsOf(abiJson: String): List<ParsedAbiMethod> =
        readAbiMethods(abiJson).map { raw -> ParsedAbiMethod(name = raw.name, inputs = raw.inputs, outputs = raw.outputs) }

    override fun entriesOf(abiJson: String): List<ParsedAbiEntry> =
        parseAbiArray(abiJson).mapNotNull(::readEntry)

    /**
     * Valida [abiJson] (JSON bem-formado + raiz é array) e devolve o array já parseado — trecho comum
     * a [readAbiEvents]/[readAbiMethods]/[entriesOf], nenhum dos 3 toca em Web3j nesta etapa.
     */
    private fun parseAbiArray(abiJson: String): JsonNode {
        val root = try {
            objectMapper.readTree(abiJson)
        } catch (ex: Exception) {
            throw InvalidAbiContentException("ABI não é um JSON válido: ${ex.message}", ex)
        }
        if (root == null || root.isMissingNode || !root.isArray) {
            throw InvalidAbiContentException("ABI deve ser um array JSON de itens (eventos, funções etc.).")
        }
        return root
    }

    /**
     * Trecho comum a [parse] e [eventsOf]: extrai, de cada item `"type": "event"`, o nome + a lista de
     * inputs na ordem de declaração Solidity. **Não** toca em Web3j (`TypeReference`/`EventEncoder`) —
     * isso é exclusivo de [toDescriptor], usado só por [parse].
     */
    private fun readAbiEvents(abiJson: String): List<RawAbiEvent> =
        parseAbiArray(abiJson).filter { it.path("type").asText() == "event" }.map(::readEvent)

    private fun readEvent(node: JsonNode): RawAbiEvent {
        val name = node.path("name").asText(null)
        if (name.isNullOrBlank()) {
            throw InvalidAbiContentException("Evento sem `name` no ABI: $node")
        }
        val inputs = node.path("inputs").map { input ->
            AbiEventInput(
                name = input.path("name").asText(""),
                type = input.path("type").asText(""),
                indexed = input.path("indexed").asBoolean(false),
            )
        }
        return RawAbiEvent(name, inputs)
    }

    private fun toDescriptor(raw: RawAbiEvent): EventDescriptor {
        val typeReferences: List<TypeReference<*>> = raw.inputs.map { input ->
            try {
                TypeReference.makeTypeReference(input.type, input.indexed, false)
            } catch (ex: Exception) {
                // makeTypeReference só declara ClassNotFoundException, mas AbiTypes.getType lança
                // UnsupportedOperationException (unchecked) para tipos não suportados (ex.: tuple/struct
                // — fora de escopo desta PoC, ver abi-strategy-design.md) — captura ampla de propósito,
                // pra nunca deixar uma exceção crua do Web3j vazar do parser.
                throw InvalidAbiContentException(
                    "Tipo Solidity não suportado no evento '${raw.name}', parâmetro '${input.name}': '${input.type}' (${ex.message})",
                    ex,
                )
            }
        }
        val event = Event(raw.name, typeReferences)
        val topic0 = EventEncoder.encode(event)
        return EventDescriptor(name = raw.name, inputs = raw.inputs, event = event, topic0 = topic0)
    }

    /** Evento cru — nome + inputs — antes de qualquer processamento Web3j. Uso interno do parser. */
    private data class RawAbiEvent(val name: String, val inputs: List<AbiEventInput>)

    /**
     * Trecho comum a [methodsOf], mirror de [readAbiEvents]: valida o ABI JSON e extrai, de cada item
     * `"type": "function"` com `stateMutability` `view`/`pure`, o nome + inputs + outputs na ordem de
     * declaração Solidity. Nunca toca em Web3j (`Function`/`FunctionEncoder`) — isso é responsabilidade
     * de [br.com.bbchain.evex.indexer.adapters.outbound.blockchain.Web3jBlockchainClient.call], não do
     * catálogo de leitura.
     */
    private fun readAbiMethods(abiJson: String): List<RawAbiMethod> =
        parseAbiArray(abiJson)
            .filter { it.path("type").asText() == "function" && it.path("stateMutability").asText() in READ_ONLY_STATE_MUTABILITIES }
            .map(::readMethod)

    private fun readMethod(node: JsonNode): RawAbiMethod {
        val name = node.path("name").asText(null)
        if (name.isNullOrBlank()) {
            throw InvalidAbiContentException("Função sem `name` no ABI: $node")
        }
        val inputs = node.path("inputs").map { input ->
            CatalogedMethodInput(
                name = input.path("name").asText(""),
                type = input.path("type").asText(""),
            )
        }
        val outputs = node.path("outputs").map { output ->
            CatalogedMethodOutput(
                // Saída anônima de tupla de retorno (ex.: `returns (uint256, uint256)`) não tem `name`
                // no Solidity — tratada como `null`, nunca inventada (DEC-082 item 2): o casamento de
                // `EnrichmentOutputMapping.outputIndex` é sempre por posição, o nome é só dica de UI.
                name = output.path("name").asText("").takeIf { it.isNotBlank() },
                type = output.path("type").asText(""),
            )
        }
        return RawAbiMethod(name, inputs, outputs)
    }

    /** Função de leitura crua — nome + inputs + outputs — antes de virar [ParsedAbiMethod]. */
    private data class RawAbiMethod(
        val name: String,
        val inputs: List<CatalogedMethodInput>,
        val outputs: List<CatalogedMethodOutput>,
    )

    /**
     * Mirror mais largo de [readEvent]/[readMethod] (E24) — lê **qualquer** item de ABI, sem exigir
     * `name` (constructor/fallback/receive nunca têm) e sem lançar em nome vazio (vira `null`, nunca
     * `""`, mesmo racional de [CatalogedMethodOutput.name]). `"type"` ausente ou desconhecido devolve
     * `null` (item ignorado por [mapNotNull] em [entriesOf]) — não deveria ocorrer num ABI válido, mas
     * não é motivo pra lançar [InvalidAbiContentException] (diferente de JSON malformado).
     */
    private fun readEntry(node: JsonNode): ParsedAbiEntry? = when (node.path("type").asText()) {
        "function" -> ParsedAbiEntry(
            kind = AbiEntryKind.FUNCTION,
            name = entryNameOf(node),
            stateMutability = node.path("stateMutability").asText(null),
            inputs = paramsOf(node, "inputs", withIndexed = false),
            outputs = paramsOf(node, "outputs", withIndexed = false),
            anonymous = null,
        )
        "event" -> ParsedAbiEntry(
            kind = AbiEntryKind.EVENT,
            name = entryNameOf(node),
            stateMutability = null,
            inputs = paramsOf(node, "inputs", withIndexed = true),
            outputs = emptyList(),
            anonymous = node.path("anonymous").asBoolean(false),
        )
        "error" -> ParsedAbiEntry(
            kind = AbiEntryKind.ERROR,
            name = entryNameOf(node),
            stateMutability = null,
            // Erro Solidity (`error Foo(...)`) só tem parâmetros de entrada, sem retorno.
            inputs = paramsOf(node, "inputs", withIndexed = false),
            outputs = emptyList(),
            anonymous = null,
        )
        "constructor" -> ParsedAbiEntry(
            kind = AbiEntryKind.CONSTRUCTOR,
            name = null,
            stateMutability = node.path("stateMutability").asText(null),
            inputs = paramsOf(node, "inputs", withIndexed = false),
            outputs = emptyList(),
            anonymous = null,
        )
        "fallback" -> ParsedAbiEntry(
            kind = AbiEntryKind.FALLBACK,
            name = null,
            stateMutability = node.path("stateMutability").asText(null),
            // Fallback não declara parâmetros no ABI (recebe sempre calldata cru).
            inputs = emptyList(),
            outputs = emptyList(),
            anonymous = null,
        )
        "receive" -> ParsedAbiEntry(
            kind = AbiEntryKind.RECEIVE,
            name = null,
            // `receive` só existe com `stateMutability: "payable"` no Solidity — nunca outro valor.
            stateMutability = "payable",
            inputs = emptyList(),
            outputs = emptyList(),
            anonymous = null,
        )
        else -> null
    }

    /** Nome do item (função/evento/erro) — `null` se ausente/vazio, nunca `""`. */
    private fun entryNameOf(node: JsonNode): String? = node.path("name").asText("").takeIf { it.isNotBlank() }

    /** Lê `inputs`/`outputs` de um item de ABI, na ordem de declaração; `indexed` só se aplica a eventos. */
    private fun paramsOf(node: JsonNode, field: String, withIndexed: Boolean): List<CatalogedAbiEntryParam> =
        node.path(field).map { param ->
            CatalogedAbiEntryParam(
                name = param.path("name").asText("").takeIf { it.isNotBlank() },
                type = param.path("type").asText(""),
                indexed = if (withIndexed) param.path("indexed").asBoolean(false) else null,
            )
        }

    companion object {
        private val READ_ONLY_STATE_MUTABILITIES = setOf("view", "pure")
    }
}
