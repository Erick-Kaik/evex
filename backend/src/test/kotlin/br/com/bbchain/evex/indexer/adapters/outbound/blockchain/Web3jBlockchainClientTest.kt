package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.response.Log

/**
 * Unit test isolado — **sem HTTP real, sem nó Besu/Hardhat** (E12 bloqueado, DEC-010). Constrói
 * respostas Web3j fabricadas em memória (`Log`, `Response.Error`) pra exercitar só o mapeamento
 * `Log -> BlockchainLog` e a heurística de detecção de erro de range (`isRangeError`) — nunca chama
 * `getLogs`/`currentBlockNumber`/`getBlockTimestamp` de verdade (isso exigiria um nó real ou fabricar
 * um `Web3jService` inteiro, fora do escopo desta etapa).
 */
class Web3jBlockchainClientTest {

    private val client = Web3jBlockchainClient(BlockchainProperties())

    @Test
    fun `maps a Web3j Log to the domain BlockchainLog`() {
        val log = Log().apply {
            setAddress("0x111111111111111111111111111111111111111a")
            setBlockNumber("0x64") // 100
            setBlockHash("0xblockhash")
            setTransactionHash("0xtxhash")
            setLogIndex("0x2") // 2
            setTopics(listOf("0xtopic0", "0xtopic1"))
            setData("0xdeadbeef")
        }

        val result = with(client) { log.toBlockchainLog() }

        assertThat(result.contractAddress).isEqualTo("0x111111111111111111111111111111111111111a")
        assertThat(result.blockNumber).isEqualTo(100L)
        assertThat(result.blockHash).isEqualTo("0xblockhash")
        assertThat(result.txHash).isEqualTo("0xtxhash")
        assertThat(result.logIndex).isEqualTo(2L)
        assertThat(result.topics).containsExactly("0xtopic0", "0xtopic1")
        assertThat(result.data).isEqualTo("0xdeadbeef")
    }

    @Test
    fun `detects a range error by the known JSON-RPC error code`() {
        val error = Response.Error(-32005, "limit exceeded")

        assertThat(client.isRangeError(error)).isTrue()
    }

    @Test
    fun `detects a range error by a known message keyword, case-insensitively`() {
        val error = Response.Error(-32000, "Query returned more than 10000 results")

        assertThat(client.isRangeError(error)).isTrue()
    }

    @Test
    fun `detects a Besu-style block range too large message`() {
        val error = Response.Error(-32000, "Requested range exceeds the range limit")

        assertThat(client.isRangeError(error)).isTrue()
    }

    @Test
    fun `does not flag an unrelated server error as a range error`() {
        val error = Response.Error(-32000, "execution reverted")

        assertThat(client.isRangeError(error)).isFalse()
    }
}
