package br.com.bbchain.evex.indexer.application.block

import br.com.bbchain.evex.indexer.config.BlockResolutionProperties
import br.com.bbchain.evex.indexer.domain.block.BlockBracket
import br.com.bbchain.evex.indexer.domain.block.BlockPoint
import br.com.bbchain.evex.indexer.domain.block.BlockTimestampLookupPort
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Cliente de rede SIMULADO (cadeia sintética monotônica) + porta de bracket falsa — fixtures neutras, sem rede. */
class BlockResolutionServiceTest {

    /** `timestampOf(n)` não decrescente; conta TODAS as chamadas RPC (a hipótese verificada: <= 32). */
    private class FakeChain(val tip: Long, val timestampOf: (Long) -> Instant, var available: Boolean = true) : BlockchainPort {
        var rpcCalls = 0
        val queriedBlocks = mutableListOf<Long>()
        var currentBlockCalls = 0

        override fun currentBlockNumber(): Long {
            rpcCalls++
            currentBlockCalls++
            check(available) { "rpc down" }
            return tip
        }

        override fun getBlockTimestamp(blockNumber: Long): Instant {
            rpcCalls++
            queriedBlocks += blockNumber
            check(available) { "rpc down" }
            return timestampOf(blockNumber)
        }

        override fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog> = error("não usado")
        override fun hasCode(address: String): Boolean = error("não usado")
        override fun call(c: String, m: String, i: List<String>, o: List<String>, a: List<String>, block: CallBlock): List<String> = error("não usado")
        override fun probeStateAt(address: String, block: Long): ProbeResult = error("não usado")
    }

    private class FakeLookup(var bracket: BlockBracket = BlockBracket(null, null), var fail: Boolean = false) : BlockTimestampLookupPort {
        var calls = 0
        override fun findBlockBracket(instant: Instant): BlockBracket {
            calls++
            if (fail) error("db down")
            return bracket
        }
    }

    private val base = Instant.parse("2026-01-01T00:00:00Z")

    /** Bloco `n` tem timestamp `base + 2n` segundos (genesis = base). */
    private val linear: (Long) -> Instant = { base.plusSeconds(it * 2) }

    private fun service(chain: BlockchainPort, lookup: BlockTimestampLookupPort? = null, maxRpcCalls: Int = 32) =
        BlockResolutionService(chain, lookup, BlockResolutionProperties().also { it.maxRpcCalls = maxRpcCalls })

    @Test
    fun `adjacent ingested bracket resolves exactly with no RPC at all`() {
        val chain = FakeChain(1_000, linear)
        val lookup = FakeLookup(BlockBracket(BlockPoint(10, linear(10)), BlockPoint(11, linear(11))))
        val date = linear(10).plusSeconds(1)

        val floor = service(chain, lookup).resolve(date, "FLOOR")
        val ceiling = service(chain, lookup).resolve(date, "CEILING")

        assertThat(floor.blockNumber).isEqualTo(10)
        assertThat(ceiling.blockNumber).isEqualTo(11)
        assertThat(floor.source).isEqualTo(BlockResolutionSource.INGESTED)
        assertThat(floor.approximate).isFalse()
        assertThat(chain.rpcCalls).isZero()
    }

    @Test
    fun `no ingested data - pure RPC bisection is exact and within the 32-call cap on a huge chain`() {
        // 100M blocos (~3 anos a 1s/bloco): ~27 passos de bisseção + gênese + eth_blockNumber + ts do topo = ~30 <= 32.
        // (Cadeias > ~2^29 blocos não cabem no teto e degradam para aproximado — coberto no teste de orçamento.)
        val tip = 100_000_000L
        val chain = FakeChain(tip, linear)
        val target = 61_234_567L
        val date = linear(target).plusSeconds(1) // entre target e target+1

        val floor = service(chain, FakeLookup()).resolve(date, "FLOOR")

        assertThat(floor.blockNumber).isEqualTo(target)
        assertThat(floor.blockTimestamp).isEqualTo(linear(target))
        assertThat(floor.source).isEqualTo(BlockResolutionSource.RPC)
        assertThat(floor.approximate).isFalse()
        assertThat(chain.rpcCalls).isLessThanOrEqualTo(32)
        assertThat(chain.currentBlockCalls).isEqualTo(1)
    }

    @Test
    fun `FLOOR and CEILING agree with a brute-force reference for many dates`() {
        val chain = FakeChain(500, linear)
        val svc = service(chain)
        for (offset in listOf(-5L, 0L, 1L, 2L, 3L, 7L, 499L, 500L, 997L, 998L, 999L, 1000L, 1001L, 1500L)) {
            val date = base.plusSeconds(offset)
            val expectedFloor = (0L..500L).lastOrNull { linear(it) <= date }
            val expectedCeiling = (0L..500L).firstOrNull { linear(it) >= date }

            assertThat(svc.resolve(date, "FLOOR").blockNumber).describedAs("FLOOR +$offset").isEqualTo(expectedFloor)
            assertThat(svc.resolve(date, "CEILING").blockNumber).describedAs("CEILING +$offset").isEqualTo(expectedCeiling)
        }
    }

    @Test
    fun `ingested bracket narrows the bisection - source COMBINED, no eth_blockNumber needed, queries stay inside the bracket`() {
        val chain = FakeChain(1_000_000, linear)
        val lookup = FakeLookup(BlockBracket(BlockPoint(100, linear(100)), BlockPoint(200, linear(200))))
        val date = linear(150).plusSeconds(1)

        val result = service(chain, lookup).resolve(date, "FLOOR")

        assertThat(result.blockNumber).isEqualTo(150)
        assertThat(result.source).isEqualTo(BlockResolutionSource.COMBINED)
        assertThat(result.approximate).isFalse()
        assertThat(chain.currentBlockCalls).isZero()
        assertThat(chain.queriedBlocks).allMatch { it in 101..199 }
        assertThat(chain.rpcCalls).isLessThanOrEqualTo(8) // log2(100) ~ 7
    }

    @Test
    fun `date before genesis - FLOOR has no block, CEILING is the genesis block, both warn`() {
        val chain = FakeChain(100, linear)
        val date = base.minusSeconds(60)

        val floor = service(chain).resolve(date, "FLOOR")
        val ceiling = service(chain).resolve(date, "CEILING")

        assertThat(floor.blockNumber).isNull()
        assertThat(floor.warnings).contains(BlockResolutionWarnings.DATE_BEFORE_GENESIS)
        assertThat(ceiling.blockNumber).isEqualTo(0)
        assertThat(ceiling.warnings).contains(BlockResolutionWarnings.DATE_BEFORE_GENESIS)
    }

    @Test
    fun `date after the head - FLOOR is the head with a warning, CEILING has no block yet`() {
        val chain = FakeChain(100, linear)
        val date = linear(100).plusSeconds(3600)

        val floor = service(chain).resolve(date, "FLOOR")
        val ceiling = service(chain).resolve(date, "CEILING")

        assertThat(floor.blockNumber).isEqualTo(100)
        assertThat(floor.warnings).contains(BlockResolutionWarnings.DATE_AFTER_LATEST)
        assertThat(ceiling.blockNumber).isNull()
        assertThat(ceiling.warnings).contains(BlockResolutionWarnings.DATE_AFTER_LATEST)
    }

    @Test
    fun `repeated timestamps - FLOOR takes the highest block and CEILING the lowest block with that timestamp`() {
        // blocos 0-4 = base, 5-9 = base+10s, 10-14 = base+20s ...
        val chain = FakeChain(49, { base.plusSeconds((it / 5) * 10) })
        val date = base.plusSeconds(10)

        assertThat(service(chain).resolve(date, "FLOOR").blockNumber).isEqualTo(9)
        assertThat(service(chain).resolve(date, "CEILING").blockNumber).isEqualTo(5)
    }

    @Test
    fun `repeated timestamps - an ingested block with the exact timestamp is refined by RPC instead of trusted`() {
        val chain = FakeChain(49, { base.plusSeconds((it / 5) * 10) })
        val date = base.plusSeconds(10)
        // o bloco 5 foi ingerido (ts == data) mas o 9 é o FLOOR real.
        val lookup = FakeLookup(BlockBracket(BlockPoint(5, date), BlockPoint(5, date)))

        val result = service(chain, lookup).resolve(date, "FLOOR")

        assertThat(result.blockNumber).isEqualTo(9)
        assertThat(result.approximate).isFalse()
    }

    @Test
    fun `without RPC it degrades to the ingested bound, approximate, with a warning`() {
        val chain = FakeChain(1_000, linear, available = false)
        val lookup = FakeLookup(BlockBracket(BlockPoint(100, linear(100)), BlockPoint(200, linear(200))))
        val date = linear(150)

        val floor = service(chain, lookup).resolve(date, "FLOOR")
        val ceiling = service(chain, lookup).resolve(date, "CEILING")

        assertThat(floor.blockNumber).isEqualTo(100)
        assertThat(floor.approximate).isTrue()
        assertThat(floor.source).isEqualTo(BlockResolutionSource.INGESTED)
        assertThat(floor.warnings).contains(BlockResolutionWarnings.RPC_UNAVAILABLE_USING_INGESTED)
        assertThat(ceiling.blockNumber).isEqualTo(200)
        assertThat(ceiling.approximate).isTrue()
    }

    @Test
    fun `without RPC and without ingested data there is no block`() {
        val chain = FakeChain(1_000, linear, available = false)

        val result = service(chain, FakeLookup()).resolve(base.plusSeconds(10), "FLOOR")

        assertThat(result.blockNumber).isNull()
        assertThat(result.approximate).isTrue()
        assertThat(result.warnings).contains(BlockResolutionWarnings.RPC_UNAVAILABLE_USING_INGESTED, BlockResolutionWarnings.NO_INGESTED_DATA)
    }

    @Test
    fun `an exhausted RPC budget returns the best bound found so far as approximate`() {
        val chain = FakeChain(3_000_000_000L, linear)
        val target = 1_234_567_890L

        val result = service(chain, maxRpcCalls = 6).resolve(linear(target).plusSeconds(1), "FLOOR")

        assertThat(chain.rpcCalls).isEqualTo(6)
        assertThat(result.approximate).isTrue()
        assertThat(result.warnings).contains(BlockResolutionWarnings.RPC_BUDGET_EXHAUSTED)
        assertThat(result.blockNumber).isLessThanOrEqualTo(target) // FLOOR parcial nunca passa do alvo
    }

    @Test
    fun `a failing ingested lookup is reported and RPC still resolves exactly`() {
        val chain = FakeChain(500, linear)

        val result = service(chain, FakeLookup(fail = true)).resolve(linear(250).plusSeconds(1), "FLOOR")

        assertThat(result.blockNumber).isEqualTo(250)
        assertThat(result.warnings).contains(BlockResolutionWarnings.INGESTED_LOOKUP_FAILED)
    }

    @Test
    fun `service works with no lookup bean at all (RPC only)`() {
        val result = service(FakeChain(500, linear), lookup = null).resolve(linear(10).plusSeconds(1), "FLOOR")

        assertThat(result.blockNumber).isEqualTo(10)
    }

    @Test
    fun `unknown direction is rejected clearly`() {
        assertThatThrownBy { service(FakeChain(1, linear)).resolve(base, "NEAREST") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("NEAREST")
    }
}
