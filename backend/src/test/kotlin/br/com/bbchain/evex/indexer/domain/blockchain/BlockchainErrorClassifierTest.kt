package br.com.bbchain.evex.indexer.domain.blockchain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BlockchainErrorClassifierTest {

    @Test
    fun `execution reverted message is REVERTED regardless of case`() {
        assertThat(BlockchainErrorClassifier.classify(-32000, "Execution Reverted: nope", null)).isEqualTo("REVERTED")
    }

    @Test
    fun `recognizable revert data (Error(string) or Panic selector) is REVERTED`() {
        assertThat(BlockchainErrorClassifier.classify(3, "call failed", "0x08c379a0" + "00".repeat(8))).isEqualTo("REVERTED")
        assertThat(BlockchainErrorClassifier.classify(3, "call failed", "0x4e487b71" + "00".repeat(8))).isEqualTo("REVERTED")
    }

    @Test
    fun `explicit state indications are STATE_UNAVAILABLE_SUSPECTED`() {
        listOf("missing trie node abc", "State not available for block", "world state is not available", "data has been pruned").forEach {
            assertThat(BlockchainErrorClassifier.classify(-32000, it, null)).describedAs(it).isEqualTo("STATE_UNAVAILABLE_SUSPECTED")
        }
    }

    @Test
    fun `a generic -32603 Internal error is NODE_ERROR and never claims missing historical state`() {
        assertThat(BlockchainErrorClassifier.classify(-32603, "Internal error", null)).isEqualTo("NODE_ERROR")
        assertThat(BlockchainErrorClassifier.classify(-32603, "Internal error", "0x")).isEqualTo("NODE_ERROR")
    }

    @Test
    fun `generic -32000 is NODE_ERROR and unknown codes are UNCLASSIFIED`() {
        assertThat(BlockchainErrorClassifier.classify(-32000, "something odd", null)).isEqualTo("NODE_ERROR")
        assertThat(BlockchainErrorClassifier.classify(-32601, "Method not found", null)).isEqualTo("UNCLASSIFIED")
        assertThat(BlockchainErrorClassifier.classify(null, null, null)).isEqualTo("UNCLASSIFIED")
    }

    @Test
    fun `fromRpcError keeps raw code message and data and the exception message has no URL`() {
        val ex = BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", "0xdead")

        assertThat(ex.code).isEqualTo(-32603)
        assertThat(ex.rpcMessage).isEqualTo("Internal error")
        assertThat(ex.data).isEqualTo("0xdead")
        assertThat(ex.classification).isEqualTo("NODE_ERROR")
        assertThat(ex.message).contains("-32603").contains("Internal error").doesNotContain("http")
    }

    @Test
    fun `transport failures carry no URL, only the exception type`() {
        val cause = java.net.SocketTimeoutException("timeout connecting to http://sink.invalid:8545")
        val ex = BlockchainCallException.transport("eth_call", BlockchainErrorClassification.TIMEOUT, cause)

        assertThat(ex.code).isNull()
        assertThat(ex.classification).isEqualTo("TIMEOUT")
        assertThat(ex.message).contains("SocketTimeoutException").doesNotContain("sink.invalid").doesNotContain("http")
    }
}
