package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.RpcRangeException
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
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.NumericType
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
 * **Não validado contra nó Besu/Hardhat real** (E12 bloqueado, DEC-010) — a heurística de detecção de
 * erro de range em [isRangeError] é uma escolha de implementação de melhor esforço (ver
 * docs/decisions.md para o detalhamento), a revisar assim que houver um nó real para testar contra.
 */
@Component
class Web3jBlockchainClient(
    properties: BlockchainProperties,
) : BlockchainPort {

    // `internal var` (não `private val`) pelo mesmo motivo de `isRangeError`/`toBlockchainLog` abaixo:
    // permite ao [Web3jBlockchainClientTest] substituir por um `Web3j` mockado (MockK) sem HTTP real,
    // já que o `test` source set é associado ao `main` pelo plugin Kotlin e enxerga `internal`.
    internal var web3j: Web3j = Web3j.build(HttpService(properties.rpcUrl))

    override fun currentBlockNumber(): Long =
        web3j.ethBlockNumber().send().blockNumber.toLong()

    override fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog> {
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
     * [AbiDecodingAdapter] já faz pra decodificar parâmetros de evento. Cobre os tipos escalares
     * usados pelos cenários da E19 (`address`/`bool`/`string`/`bytesN`/`bytes`/`uintN`/`intN`); arrays
     * e tuplas **não são suportados aqui** — `AbiTypes.getType` não distingue a forma de codificar um
     * argumento de array a partir de uma única string, e nenhuma decisão foi tomada em DEC-082 sobre
     * qual convenção usar (ex.: CSV, JSON array) pra representar isso num único `String` de [args] —
     * registrar como complemento da DEC-082 se/quando um cenário real precisar disso.
     */
    private fun toSolidityValue(solidityType: String, rawValue: String): Type<*> {
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
                "Tipo Solidity '$solidityType' não suportado em eth_call (E19/DEC-082) — só tipos " +
                    "escalares; arrays/tuplas ficam para uma decisão futura.",
            )
        }
    }

    /**
     * Formata o valor decodificado de uma saída de `eth_call` como `String` — mesmo racional de
     * `AbiDecodingAdapter.formatValue` (`bytes`/`bytesN` viram hex string, arrays Solidity descem um
     * nível antes de formatar), só que devolvendo sempre `String` (não `Any?`), porque
     * [BlockchainPort.call] devolve `List<String>` por contrato de porta (E19/DEC-082).
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
