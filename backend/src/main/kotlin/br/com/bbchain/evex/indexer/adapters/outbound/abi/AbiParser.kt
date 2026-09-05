package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiEventCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.CatalogedEventInput
import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiEvent
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
 */
@Component
class AbiParser : AbiEventCatalogPort {
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

    /**
     * Trecho comum a [parse] e [eventsOf]: valida o ABI JSON e extrai, de cada item `"type": "event"`,
     * o nome + a lista de inputs na ordem de declaração Solidity. **Não** toca em Web3j
     * (`TypeReference`/`EventEncoder`) — isso é exclusivo de [toDescriptor], usado só por [parse].
     */
    private fun readAbiEvents(abiJson: String): List<RawAbiEvent> {
        val root = try {
            objectMapper.readTree(abiJson)
        } catch (ex: Exception) {
            throw InvalidAbiContentException("ABI não é um JSON válido: ${ex.message}", ex)
        }
        if (root == null || root.isMissingNode || !root.isArray) {
            throw InvalidAbiContentException("ABI deve ser um array JSON de itens (eventos, funções etc.).")
        }
        return root.filter { it.path("type").asText() == "event" }.map(::readEvent)
    }

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
}
