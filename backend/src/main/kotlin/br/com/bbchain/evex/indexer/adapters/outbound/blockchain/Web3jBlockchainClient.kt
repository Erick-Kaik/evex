package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.RpcRangeException
import com.fasterxml.jackson.core.type.TypeReference as JacksonTypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.time.Instant
import org.springframework.stereotype.Component
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.AbiTypes
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.BytesType
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.NumericType
import org.web3j.abi.datatypes.StaticArray
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterNumber
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric

/**
 * Implementação de [BlockchainPort] via Web3j `HttpService` direto — **sem BBChain Keeper** (regra
 * não-negociável do projeto). Só polling (DEC-003), `eth_getLogs` sem filtro de endereço (DEC-001).
 *
 * Autenticação opcional (E27/DEC-094 PAT estático; E27 complemento/DEC-095 token Keycloak dinâmico) —
 * ver [refreshAuthHeader]/[resolveAuthHeaders].
 *
 * **Não validado contra nó Besu/Hardhat real** (E12 bloqueado, DEC-010) — a heurística de detecção de
 * erro de range em [isRangeError] é uma escolha de implementação de melhor esforço (ver
 * docs/decisions.md para o detalhamento), a revisar assim que houver um nó real para testar contra.
 */
@Component
class Web3jBlockchainClient(
    private val properties: BlockchainProperties,
    private val keycloakTokenProvider: KeycloakTokenProvider,
) : BlockchainPort {

    // Referência à própria `HttpService` (não só ao `Web3j` derivado dela) — necessária pro complemento
    // Keycloak (E27/DEC-095): `HttpService.headers` é um `HashMap` mutável, lido de novo por
    // `buildHeaders()` a cada `performIO` (1 request), confirmado no source real do jar
    // (`core-6.0.0-sources.jar`, `org.web3j.protocol.http.HttpService`) — dá pra sobrescrever a entrada
    // `Authorization` nela (via `addHeaders`) antes de cada request, sem recriar `HttpService`/`Web3j`.
    // Isso é o que permite um token Keycloak (expira em minutos) continuar válido ao longo da vida do
    // processo sem recriar a conexão.
    internal var httpService: HttpService = HttpService(properties.rpcUrl)

    // `internal var` (não `private val`) pelo mesmo motivo de `isRangeError`/`toBlockchainLog` abaixo:
    // permite ao [Web3jBlockchainClientTest] substituir por um `Web3j` mockado (MockK) sem HTTP real,
    // já que o `test` source set é associado ao `main` pelo plugin Kotlin e enxerga `internal`.
    internal var web3j: Web3j = Web3j.build(httpService)

    // Recalcula o header de auth (PAT estático ou token Keycloak, ver `resolveAuthHeaders`) e o
    // sobrescreve na `HttpService` viva antes de qualquer request de verdade — chamado no início de
    // cada método público que efetivamente fala com o nó. Sem Keycloak/PAT configurado,
    // `resolveAuthHeaders` devolve mapa vazio e este `addHeaders` é um no-op (comportamento idêntico ao
    // de antes desta property existir).
    private fun refreshAuthHeader() {
        httpService.addHeaders(resolveAuthHeaders(properties, keycloakTokenProvider))
    }

    // Só pra decodificar a convenção de argumento de array (DEC-090) — mesmo padrão de
    // `WebhookEnrichmentResolver.objectMapper` (instância própria, não injetada; não há necessidade de
    // config customizada aqui, só `readValue` de um JSON array simples de strings).
    private val objectMapper = ObjectMapper()

    override fun currentBlockNumber(): Long {
        refreshAuthHeader()
        return web3j.ethBlockNumber().send().blockNumber.toLong()
    }

    override fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog> {
        refreshAuthHeader()
        // `address = null`: EthFilter/Filter é `@JsonInclude(NON_NULL)`, então o campo é omitido do
        // corpo JSON-RPC — o nó interpreta ausência de `address` como "qualquer endereço", diferente
        // de uma lista vazia (que alguns clientes podem tratar como "nenhum endereço"). DEC-001/DEC-002:
        // rede inteira, sem filtro de endereço.
        val filter = EthFilter(
            DefaultBlockParameterNumber(fromBlock),
            DefaultBlockParameterNumber(toBlock),
            null as List<String>?,
        )
        val response: EthLog = web3j.ethGetLogs(filter).send()
        if (response.hasError()) {
            val error = response.error
            if (isRangeError(error)) {
                throw RpcRangeException("eth_getLogs recusado por range (code=${error.code}): ${error.message}")
            }
            throw IllegalStateException("eth_getLogs falhou (code=${error.code}): ${error.message}")
        }
        return response.logs
            .map { it.get() as Log }
            .map { it.toBlockchainLog() }
    }

    override fun getBlockTimestamp(blockNumber: Long): Instant {
        refreshAuthHeader()
        val block = web3j.ethGetBlockByNumber(DefaultBlockParameterNumber(blockNumber), false).send().block
            ?: throw IllegalStateException("Bloco $blockNumber não encontrado (eth_getBlockByNumber retornou nulo)")
        return Instant.ofEpochSecond(block.timestamp.toLong())
    }

    // Primeiro uso de `eth_getCode` no Evex (E22/DEC-080). Mesmo padrão de tratamento de erro de
    // `currentBlockNumber`/`getBlockTimestamp`: sem checagem de `hasError()` própria — falha de
    // transporte (`IOException`) sobe crua para quem chamou. Não é candidato a `RpcRangeException`
    // (essa é exclusiva de `eth_getLogs`, que pode ser recusado por range grande demais; `eth_getCode`
    // é uma consulta pontual de 1 endereço em 1 bloco, sem noção de "range").
    override fun hasCode(address: String): Boolean {
        refreshAuthHeader()
        val code = web3j.ethGetCode(address, DefaultBlockParameter.valueOf("latest")).send().code
        return code != "0x"
    }

    // E19/DEC-082 ("Perfil de Enriquecimento"): eth_call de leitura no bloco histórico informado (não
    // `latest`). Mesmo padrão de tratamento de erro de `currentBlockNumber`/`getBlockTimestamp`/
    // `hasCode`: sem checagem de `hasError()` própria (essa é exclusiva de `getLogs`, que precisa
    // distinguir erro de range pra disparar auto-shrink) — se o nó retornar erro JSON-RPC, o campo
    // `result` vem nulo (JSON-RPC nunca preenche `result` e `error` juntos), e isso já basta pra
    // detectar a falha aqui; qualquer outra falha (timeout, `IOException`) sobe crua, igual às demais.
    override fun call(
        contractAddress: String,
        methodName: String,
        inputTypes: List<String>,
        outputTypes: List<String>,
        args: List<String>,
        blockNumber: Long,
    ): List<String> {
        refreshAuthHeader()
        val inputParameters: List<Type<*>> = inputTypes.zip(args).map { (type, value) -> toSolidityValue(type, value) }
        val outputParameters: List<TypeReference<*>> = outputTypes.map { TypeReference.makeTypeReference(it) }
        val function = Function(methodName, inputParameters, outputParameters)
        val encodedFunction = FunctionEncoder.encode(function)
        val transaction = Transaction.createEthCallTransaction(null, contractAddress, encodedFunction)
        val response = web3j.ethCall(transaction, DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber))).send()
        val result = response.value
            ?: throw IllegalStateException(
                "eth_call em $contractAddress.$methodName (bloco $blockNumber) não retornou resultado " +
                    "(code=${response.error?.code}): ${response.error?.message}",
            )
        return FunctionReturnDecoder.decode(result, function.outputParameters).map { it.toCallResultString() }
    }

    /**
     * Converte um argumento de entrada (string, na forma como chega de
     * [br.com.bbchain.evex.indexer.application.webhook.WebhookEnrichmentResolver]) pro [Type] Web3j
     * correspondente ao tipo Solidity declarado — contraparte de codificação do que
     * [AbiDecodingAdapter] já faz pra decodificar parâmetros de evento. Cobre os tipos escalares usados
     * pelos cenários da E19 (`address`/`bool`/`string`/`bytesN`/`bytes`/`uintN`/`intN`) e, desde
     * DEC-090, **arrays desses mesmos tipos escalares** (`T[]`/`T[N]`, ex. `uint256[]`, `address[3]`) —
     * detectados pelo sufixo `[...]` no fim do tipo Solidity e delegados a [toSolidityArrayValue].
     * **Tuplas/structs continuam fora de escopo** (mesma limitação já existente na decodificação de
     * evento, `AbiDecodingAdapter`) — `AbiTypes.getType` já lança `UnsupportedOperationException` pra
     * qualquer tipo desconhecido (ex. `tuple`), sem cair em nenhum dos ramos abaixo; não há decisão de
     * convenção pra representar `components`/campos aninhados num único `String` de [args], e nenhum
     * cenário real pediu isso até aqui — se pedir, é mudança maior (envolve `CatalogedMethodInput`/
     * `AbiParser`, fora do escopo desta correção pontual).
     */
    private fun toSolidityValue(solidityType: String, rawValue: String): Type<*> {
        val arrayMatch = ARRAY_TYPE_SUFFIX.matchEntire(solidityType)
        return if (arrayMatch != null) {
            val (elementType, arraySizeText) = arrayMatch.destructured
            toSolidityArrayValue(elementType, arraySizeText.ifBlank { null }, rawValue, solidityType)
        } else {
            toScalarSolidityValue(solidityType, rawValue)
        }
    }

    /** Ramo escalar de [toSolidityValue] — extraído pra ser reaproveitado por elemento de array em [toSolidityArrayValue]. */
    private fun toScalarSolidityValue(solidityType: String, rawValue: String): Type<*> {
        val typeClass = AbiTypes.getType(solidityType, false)
        return when {
            NumericType::class.java.isAssignableFrom(typeClass) ->
                typeClass.getConstructor(BigInteger::class.java).newInstance(BigInteger(rawValue)) as Type<*>
            typeClass == Address::class.java -> Address(rawValue)
            typeClass == Utf8String::class.java -> Utf8String(rawValue)
            typeClass == Bool::class.java -> Bool(rawValue.toBoolean())
            BytesType::class.java.isAssignableFrom(typeClass) ->
                typeClass.getConstructor(ByteArray::class.java).newInstance(Numeric.hexStringToByteArray(rawValue)) as Type<*>
            else -> throw IllegalArgumentException(
                "Tipo Solidity '$solidityType' não suportado em eth_call (E19/DEC-082/DEC-090) — só " +
                    "tipos escalares e arrays de escalares; tuplas/structs ficam fora de escopo (mesma " +
                    "limitação já existente na decodificação de evento).",
            )
        }
    }

    /**
     * Constrói o argumento de array Web3j (dinâmico [DynamicArray] pro sufixo `[]`, estático
     * `StaticArrayN` gerado pelo sufixo `[N]`) a partir da convenção fechada em DEC-090: [rawValue] é
     * um JSON array de strings (ex. `["1","2","3"]` pra `uint256[]`, `["0xabc...","0xdef..."]` pra
     * `address[]`) — 1 string por elemento, cada uma decodificada com o mesmo [toScalarSolidityValue]
     * usado pro tipo base fora de array. Construção por reflexão (`getConstructor(Class, List)`), mesmo
     * padrão já usado pelos ramos escalares de [toScalarSolidityValue] — evita depender de inferência de
     * tipo genérico do Kotlin contra o bound `T extends Type` (raw) do Web3j.
     */
    private fun toSolidityArrayValue(
        elementType: String,
        arraySizeText: String?,
        rawValue: String,
        originalType: String,
    ): Type<*> {
        val elements: List<String> = try {
            objectMapper.readValue(rawValue, object : JacksonTypeReference<List<String>>() {})
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Argumento de array '$originalType' não é um JSON array de strings válido (convenção " +
                    "DEC-090) — valor recebido: '$rawValue' (${ex.message})",
                ex,
            )
        }
        val elementValues: List<Type<*>> = elements.map { toScalarSolidityValue(elementType, it) }
        val elementTypeClass = AbiTypes.getType(elementType, false)

        if (arraySizeText == null) {
            return DynamicArray::class.java
                .getConstructor(Class::class.java, List::class.java)
                .newInstance(elementTypeClass, elementValues) as Type<*>
        }

        val expectedSize = arraySizeText.toInt()
        if (elementValues.size != expectedSize) {
            throw IllegalArgumentException(
                "Argumento de array estático '$originalType' esperava $expectedSize elemento(s), " +
                    "recebeu ${elementValues.size}: '$rawValue'",
            )
        }
        if (expectedSize < 1 || expectedSize > StaticArray.MAX_SIZE_OF_STATIC_ARRAY) {
            throw IllegalArgumentException(
                "Array estático '$originalType' com tamanho $expectedSize fora do suportado pelo Web3j " +
                    "(1..${StaticArray.MAX_SIZE_OF_STATIC_ARRAY}) — sem classe StaticArrayN gerada pra esse tamanho.",
            )
        }
        val staticArrayClass = Class.forName("org.web3j.abi.datatypes.generated.StaticArray$expectedSize")
        return staticArrayClass
            .getConstructor(Class::class.java, List::class.java)
            .newInstance(elementTypeClass, elementValues) as Type<*>
    }

    /**
     * Formata o valor decodificado de uma saída de `eth_call` como `String` — mesmo racional de
     * `AbiDecodingAdapter.formatValue` (`bytes`/`bytesN` viram hex string, arrays Solidity descem um
     * nível antes de formatar), só que devolvendo sempre `String` (não `Any?`), porque
     * [BlockchainPort.call] devolve `List<String>` por contrato de porta (E19/DEC-082). Já cobria saída
     * de tipo array antes de [toSolidityValue] ganhar suporte a array como **entrada** (DEC-090) —
     * `TypeReference.makeTypeReference` (usado em `outputParameters`) já sabia decodificar array, só
     * faltava construir o argumento de entrada; formato de exibição `[a, b, c]` mantido de propósito,
     * não vira JSON (não precisa ida-e-volta, é só formatação — a convenção JSON é exclusiva de
     * argumento de entrada).
     */
    private fun Type<*>.toCallResultString(): String = when (val raw = value) {
        is ByteArray -> Numeric.toHexString(raw)
        is List<*> -> raw.joinToString(prefix = "[", postfix = "]") { element ->
            if (element is Type<*>) element.toCallResultString() else element.toString()
        }
        else -> raw.toString()
    }

    /**
     * Heurística de detecção de erro de range do nó — combina o código JSON-RPC `-32005` ("limit
     * exceeded", usado por alguns provedores/clientes para range grande demais) com um conjunto de
     * palavras-chave conhecidas de mensagens de erro de `eth_getLogs` de implementações Ethereum/Besu
     * (ex.: geth "query returned more than N results", Besu "block range too large" /
     * "exceeds the range limit"). Nenhuma dessas mensagens foi validada contra um nó Besu real ainda
     * (E12/DEC-010) — ver docs/decisions.md para o racional completo e o que revisar quando isso
     * acontecer. `internal` (não `private`) de propósito: [Web3jBlockchainClientTest] exercita esta
     * heurística e o mapeamento [toBlockchainLog] isoladamente, sem HTTP real (o `test` source set é
     * associado ao `main` pelo plugin Kotlin, então enxerga `internal`).
     */
    internal fun isRangeError(error: Response.Error): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return error.code == RANGE_ERROR_CODE || RANGE_ERROR_KEYWORDS.any { message.contains(it) }
    }

    internal fun Log.toBlockchainLog() = BlockchainLog(
        contractAddress = address,
        blockNumber = blockNumber.toLong(),
        blockHash = blockHash,
        txHash = transactionHash,
        logIndex = logIndex.toLong(),
        topics = topics,
        data = data,
    )

    companion object {
        // Detecta o sufixo de array Solidity no fim do tipo (`T[]`/`T[N]`) — grupo 1 é o tipo do
        // elemento, grupo 2 é o tamanho (vazio pra dinâmico). Só o ÚLTIMO par de colchetes, de
        // propósito: não cobre array de array (`uint256[][]`) nem array de tupla — fora de escopo
        // desta correção (DEC-090), mesmo racional de "tupla continua não suportada".
        private val ARRAY_TYPE_SUFFIX = Regex("""^(.+)\[(\d*)]$""")

        private const val RANGE_ERROR_CODE = -32005
        private val RANGE_ERROR_KEYWORDS = listOf(
            "query returned more than",
            "block range",
            "range is too large",
            "range too large",
            "exceeds the range",
            "exceeds range",
            "too many results",
            "limit exceeded",
        )
    }
}
