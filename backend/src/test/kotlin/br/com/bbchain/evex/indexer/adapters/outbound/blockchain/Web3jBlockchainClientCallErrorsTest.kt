package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainCallException
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.IOException
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.DefaultBlockParameterNumber
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthCall
import org.web3j.protocol.core.methods.response.EthGetCode

/** E33 fatia 1: CallBlock, erros JSON-RPC brutos, aridade, sonda e timeouts do cliente Web3j. Sem rede (Web3j mockado). */
class Web3jBlockchainClientCallErrorsTest {

    private val properties = BlockchainProperties()
    private val client = Web3jBlockchainClient(properties, KeycloakTokenProvider(properties, RestClient.builder()))
    private val address = "0x0000000000000000000000000000000000000001"

    private fun ethCallError(code: Int, message: String, data: String? = null): EthCall = EthCall().apply {
        error = Response.Error().apply {
            this.code = code
            this.message = message
            this.data = data
        }
    }

    private fun stubEthCall(response: EthCall, blockSlot: io.mockk.CapturingSlot<DefaultBlockParameter>? = null) {
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } returns response
        val web3j = mockk<Web3j>()
        if (blockSlot != null) {
            every { web3j.ethCall(any<Transaction>(), capture(blockSlot)) } returns request
        } else {
            every { web3j.ethCall(any<Transaction>(), any<DefaultBlockParameter>()) } returns request
        }
        client.web3j = web3j
    }

    private fun callAt(block: CallBlock) = client.call(address, "totalSupply", emptyList(), listOf("uint256"), emptyList(), block)

    private val okResponse get() = EthCall().apply { setResult("0x" + FunctionEncoder.encodeConstructor(listOf(Uint256(BigInteger.TEN)))) }

    @Test
    fun `CallBlock Latest is sent as the latest block parameter`() {
        val slot = slot<DefaultBlockParameter>()
        stubEthCall(okResponse, slot)

        assertThat(callAt(CallBlock.Latest)).containsExactly("10")

        assertThat(slot.captured).isEqualTo(DefaultBlockParameterName.LATEST)
    }

    @Test
    fun `CallBlock Number is sent as the exact numeric block`() {
        val slot = slot<DefaultBlockParameter>()
        stubEthCall(okResponse, slot)

        callAt(CallBlock.Number(321))

        assertThat((slot.captured as DefaultBlockParameterNumber).blockNumber).isEqualTo(BigInteger.valueOf(321))
    }

    @Test
    fun `the legacy Long overload delegates to CallBlock Number`() {
        val slot = slot<DefaultBlockParameter>()
        stubEthCall(okResponse, slot)

        client.call(address, "totalSupply", emptyList(), listOf("uint256"), emptyList(), 55L)

        assertThat((slot.captured as DefaultBlockParameterNumber).blockNumber).isEqualTo(BigInteger.valueOf(55))
    }

    @Test
    fun `JSON-RPC error keeps raw code, message and data (data used to be discarded)`() {
        stubEthCall(ethCallError(-32603, "Internal error", "0x1234"))

        val ex = catchBlockchainCallException { callAt(CallBlock.Number(1)) }

        assertThat(ex.code).isEqualTo(-32603)
        assertThat(ex.rpcMessage).isEqualTo("Internal error")
        assertThat(ex.data).isEqualTo("0x1234")
        assertThat(ex.classification).isEqualTo("NODE_ERROR") // nunca "sem estado histórico" por código genérico
    }

    @Test
    fun `classification follows only explicit indications`() {
        stubEthCall(ethCallError(3, "execution reverted: nope", "0x08c379a0"))
        assertThat(catchBlockchainCallException { callAt(CallBlock.Latest) }.classification).isEqualTo("REVERTED")

        stubEthCall(ethCallError(-32000, "missing trie node 0xabc (path )"))
        assertThat(catchBlockchainCallException { callAt(CallBlock.Number(1)) }.classification).isEqualTo("STATE_UNAVAILABLE_SUSPECTED")

        stubEthCall(ethCallError(-32601, "Method not found"))
        assertThat(catchBlockchainCallException { callAt(CallBlock.Number(1)) }.classification).isEqualTo("UNCLASSIFIED")
    }

    @Test
    fun `timeout becomes TIMEOUT without leaking the URL`() {
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } throws SocketTimeoutException("timeout calling http://sink.invalid:8545/rpc")
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(any<Transaction>(), any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        val ex = catchBlockchainCallException { callAt(CallBlock.Latest) }

        assertThat(ex.classification).isEqualTo("TIMEOUT")
        assertThat(ex.code).isNull()
        assertThat(ex.message).doesNotContain("sink.invalid").doesNotContain("http")
    }

    @Test
    fun `other IO failures become IO without leaking the URL`() {
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } throws IOException("Connection refused to http://sink.invalid:8545")
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(any<Transaction>(), any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        val ex = catchBlockchainCallException { callAt(CallBlock.Latest) }

        assertThat(ex.classification).isEqualTo("IO")
        assertThat(ex.message).doesNotContain("sink.invalid")
    }

    @Test
    fun `args size different from inputTypes size is a clear error instead of silent zip truncation`() {
        val web3j = mockk<Web3j>(relaxed = true)
        client.web3j = web3j

        assertThatThrownBy {
            client.call(address, "balanceOf", listOf("address"), listOf("uint256"), listOf("0x01", "0x02"), CallBlock.Latest)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Aridade")
            .hasMessageContaining("1")
            .hasMessageContaining("2")
        assertThatThrownBy {
            client.call(address, "balanceOf", listOf("address", "uint256"), listOf("uint256"), listOf("0x01"), 5L)
        }.isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { web3j.ethCall(any<Transaction>(), any<DefaultBlockParameter>()) }
    }

    @Test
    fun `probeStateAt reports code presence at the block`() {
        val slot = slot<DefaultBlockParameter>()
        val request = mockk<Request<*, EthGetCode>>()
        every { request.send() } returns EthGetCode().apply { setResult("0x6080") }
        val web3j = mockk<Web3j>()
        every { web3j.ethGetCode(address, capture(slot)) } returns request
        client.web3j = web3j

        assertThat(client.probeStateAt(address, 99)).isEqualTo(ProbeResult.Ok(hasCode = true))
        assertThat((slot.captured as DefaultBlockParameterNumber).blockNumber).isEqualTo(BigInteger.valueOf(99))
    }

    @Test
    fun `probeStateAt returns the raw node error instead of throwing`() {
        val request = mockk<Request<*, EthGetCode>>()
        every { request.send() } returns EthGetCode().apply {
            error = Response.Error().apply {
                code = -32603
                message = "Internal error"
            }
        }
        val web3j = mockk<Web3j>()
        every { web3j.ethGetCode(address, any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        val result = client.probeStateAt(address, 99) as ProbeResult.Failed

        assertThat(result.code).isEqualTo(-32603)
        assertThat(result.message).isEqualTo("Internal error")
        assertThat(result.classification).isEqualTo("NODE_ERROR")
    }

    @Test
    fun `probeStateAt turns transport failure into Failed`() {
        val request = mockk<Request<*, EthGetCode>>()
        every { request.send() } throws SocketTimeoutException("t")
        val web3j = mockk<Web3j>()
        every { web3j.ethGetCode(address, any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        val result = client.probeStateAt(address, 1) as ProbeResult.Failed

        assertThat(result.classification).isEqualTo("TIMEOUT")
        assertThat(result.code).isNull()
    }

    // --- timeouts configuráveis -----------------------------------------------------------------

    @Test
    fun `http client uses the conservative configurable timeouts, defaults 5s connect and 15s read`() {
        val defaults = Web3jBlockchainClient.buildOkHttpClient(BlockchainProperties())

        assertThat(defaults.connectTimeoutMillis).isEqualTo(5_000)
        assertThat(defaults.readTimeoutMillis).isEqualTo(15_000)

        val custom = Web3jBlockchainClient.buildOkHttpClient(
            BlockchainProperties().also {
                it.connectTimeout = Duration.ofSeconds(2)
                it.readTimeout = Duration.ofSeconds(40)
            },
        )
        assertThat(custom.connectTimeoutMillis).isEqualTo(2_000)
        assertThat(custom.readTimeoutMillis).isEqualTo(40_000)
    }

    private fun catchBlockchainCallException(block: () -> Unit): BlockchainCallException {
        try {
            block()
        } catch (ex: BlockchainCallException) {
            return ex
        }
        throw AssertionError("esperava BlockchainCallException")
    }
}
