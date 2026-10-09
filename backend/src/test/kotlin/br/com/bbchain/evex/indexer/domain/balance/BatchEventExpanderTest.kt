package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.Network
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Unit test do expansor puro de eventos em lote (DEC-110) — sem Spring, sem banco. */
class BatchEventExpanderTest {

    private val from = "0x1111111111111111111111111111111111111111"
    private val to = "0x2222222222222222222222222222222222222222"

    private fun config(tokenIdParam: String? = "ids") = BalanceMonitorConfig(
        id = UUID.randomUUID(),
        contractAddress = "0xbbbb000000000000000000000000000000000002",
        network = Network.TESTNET,
        eventName = "TransferBatch",
        behavior = BalanceBehavior.TRANSFER,
        walletParam = null,
        fromParam = "from",
        toParam = "to",
        valueParam = "values",
        tokenIdParam = tokenIdParam,
        active = true,
        createdAt = Instant.now(),
        batch = true,
    )

    private fun params(ids: Any?, values: Any?) = mapOf<String, Any?>("from" to from, "to" to to, "ids" to ids, "values" to values)

    @Test
    fun `equal-sized arrays expand into one scalar map per position, replicating the other params`() {
        val result = BatchEventExpander.expand(config(), params(listOf(2, 3), listOf(10, 20)))

        assertThat(result).hasSize(2)
        assertThat(result[0]).containsEntry("ids", BigInteger.valueOf(2)).containsEntry("values", BigInteger.TEN)
            .containsEntry("from", from).containsEntry("to", to)
        assertThat(result[1]).containsEntry("ids", BigInteger.valueOf(3)).containsEntry("values", BigInteger.valueOf(20))
            .containsEntry("from", from).containsEntry("to", to)
    }

    @Test
    fun `single-element arrays expand into one map`() {
        assertThat(BatchEventExpander.expand(config(), params(listOf(7), listOf(1)))).hasSize(1)
    }

    @Test
    fun `empty arrays are a legitimate no-op`() {
        assertThat(BatchEventExpander.expand(config(), params(emptyList<Any>(), emptyList<Any>()))).isEmpty()
    }

    @Test
    fun `repeated ids are kept as separate positions (the adapter sums them)`() {
        val result = BatchEventExpander.expand(config(), params(listOf(5, 5), listOf(1, 2)))
        assertThat(result.map { it["ids"] }).containsExactly(BigInteger.valueOf(5), BigInteger.valueOf(5))
    }

    @Test
    fun `different array sizes throw and emit nothing`() {
        assertThatThrownBy { BatchEventExpander.expand(config(), params(listOf(1, 2), listOf(1))) }
            .isInstanceOf(BatchEventInvalidException::class.java)
            .hasMessageContaining("tamanhos diferentes")
    }

    @Test
    fun `a key param that is not a list throws`() {
        assertThatThrownBy { BatchEventExpander.expand(config(), params(listOf(1), BigInteger.ONE)) }
            .isInstanceOf(BatchEventInvalidException::class.java)
        assertThatThrownBy { BatchEventExpander.expand(config(), params(BigInteger.ONE, listOf(1))) }
            .isInstanceOf(BatchEventInvalidException::class.java)
    }

    @Test
    fun `a missing key param throws`() {
        assertThatThrownBy { BatchEventExpander.expand(config(), mapOf("from" to from, "to" to to)) }
            .isInstanceOf(BatchEventInvalidException::class.java)
    }

    @Test
    fun `a non-numeric element throws`() {
        assertThatThrownBy { BatchEventExpander.expand(config(), params(listOf(1, "abc"), listOf(1, 2))) }
            .isInstanceOf(BatchEventInvalidException::class.java)
            .hasMessageContaining("não é numérico")
        assertThatThrownBy { BatchEventExpander.expand(config(), params(listOf(1, 2), listOf(1, null))) }
            .isInstanceOf(BatchEventInvalidException::class.java)
    }

    @Test
    fun `mixed Int-Long-BigInteger-String elements (as re-read from JSONB) are converted element by element`() {
        val big = BigInteger.TWO.pow(200)
        val max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE)
        val result = BatchEventExpander.expand(
            config(),
            params(listOf(1, 2L, big, max.toString()), listOf(max, big, 3, "4")),
        )

        assertThat(result.map { it["ids"] }).containsExactly(BigInteger.ONE, BigInteger.TWO, big, max)
        assertThat(result.map { it["values"] }).containsExactly(max, big, BigInteger.valueOf(3), BigInteger.valueOf(4))
    }

    @Test
    fun `without tokenIdParam only the value list is expanded`() {
        val result = BatchEventExpander.expand(config(tokenIdParam = null), params(listOf(9, 9, 9), listOf(1, 2)))
        assertThat(result).hasSize(2)
        assertThat(result[0]["ids"]).isEqualTo(listOf(9, 9, 9))
    }

    @Test
    fun `requireScalar rejects a list in valueParam or tokenIdParam and suggests batch=true`() {
        val scalarConfig = config().copy(batch = false)
        assertThatThrownBy { BatchEventExpander.requireScalar(scalarConfig, params(listOf(1), 5)) }
            .isInstanceOf(BatchEventInvalidException::class.java)
            .hasMessageContaining("batch=true")
        BatchEventExpander.requireScalar(scalarConfig, params(1, 5))
    }
}
