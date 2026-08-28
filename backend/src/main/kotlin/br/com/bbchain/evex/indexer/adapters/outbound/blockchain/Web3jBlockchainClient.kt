package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.RpcRangeException
import java.time.Instant
import org.springframework.stereotype.Component
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterNumber
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import org.web3j.protocol.http.HttpService

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

    private val web3j: Web3j = Web3j.build(HttpService(properties.rpcUrl))

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
