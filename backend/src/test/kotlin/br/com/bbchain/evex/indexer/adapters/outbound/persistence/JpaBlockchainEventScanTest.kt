package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.EventCursor
import br.com.bbchain.evex.indexer.domain.event.EventScanFilter
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Leitura do E33 em [JpaBlockchainEventAdapter]: `findById`/`findAllByIds` e a varredura por cursor
 * `(blockNumber, logIndex)` (`scanDecoded`/`countDecodedWithoutTimestamp`). Fixtures neutras.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaBlockchainEventScanTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: BlockchainEventJpaRepository

    private val adapter: JpaBlockchainEventAdapter by lazy { JpaBlockchainEventAdapter(repository) }

    private val tokenA = "0x0000000000000000000000000000000000000001"
    private val tokenB = "0x0000000000000000000000000000000000000002"
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val filter = EventScanFilter(contractAddress = tokenA, eventName = "ValueChanged", network = Network.TESTNET)

    @BeforeEach
    fun clean() {
        repository.deleteAllInBatch()
    }

    private fun event(
        block: Long,
        logIndex: Long = 0,
        address: String = tokenA,
        name: String? = "ValueChanged",
        decoded: Boolean = true,
        network: Network = Network.TESTNET,
        timestamp: Instant? = t0.plusSeconds(block),
    ) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = network,
        blockNumber = block,
        blockHash = "0xblock$block",
        txHash = "0xtx-$block-$logIndex-${UUID.randomUUID()}",
        logIndex = logIndex,
        topic0 = "0xtopic0",
        rawTopics = listOf("0xtopic0"),
        rawData = "0x",
        decoded = decoded,
        eventName = if (decoded) name else null,
        decodedParameters = if (decoded) mapOf("value" to "1") else null,
        blockTimestamp = timestamp?.truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `findById and findAllByIds return existing events and skip unknown ids`() {
        val saved = adapter.saveAll(listOf(event(1), event(2), event(3, decoded = false)))

        assertThat(adapter.findById(saved[0].id)?.blockNumber).isEqualTo(1)
        assertThat(adapter.findById(saved[2].id)?.decoded).isFalse()
        assertThat(adapter.findById(UUID.randomUUID())).isNull()
        assertThat(adapter.findAllByIds(listOf(saved[0].id, saved[1].id, UUID.randomUUID())).map { it.blockNumber })
            .containsExactlyInAnyOrder(1L, 2L)
        assertThat(adapter.findAllByIds(emptyList())).isEmpty()
    }

    @Test
    fun `scanDecoded keeps only decoded events of the contract, event and network, newest first`() {
        adapter.saveAll(
            listOf(
                event(1), event(2), event(3),
                event(4, decoded = false),
                event(5, address = tokenB),
                event(6, name = "Other"),
                event(7, network = Network.MAINNET),
            ),
        )

        val result = adapter.scanDecoded(filter, after = null, limit = 10)

        assertThat(result.map { it.blockNumber }).containsExactly(3L, 2L, 1L)
    }

    @Test
    fun `cursor pagination neither repeats nor skips events, including several logs in the same block`() {
        adapter.saveAll(
            listOf(
                event(10, 0), event(10, 1), event(10, 2),
                event(11, 0), event(12, 0), event(12, 1),
            ),
        )

        val seen = mutableListOf<Pair<Long, Long>>()
        var cursor: EventCursor? = null
        do {
            val page = adapter.scanDecoded(filter, cursor, limit = 2)
            seen += page.map { it.blockNumber to it.logIndex }
            cursor = page.lastOrNull()?.let { EventCursor(it.blockNumber, it.logIndex) }
        } while (page.isNotEmpty())

        assertThat(seen).containsExactly(12L to 1L, 12L to 0L, 11L to 0L, 10L to 2L, 10L to 1L, 10L to 0L)
    }

    @Test
    fun `scanDecoded filters by block range and by date range`() {
        adapter.saveAll(listOf(event(1), event(2), event(3), event(4), event(5)))

        assertThat(adapter.scanDecoded(filter.copy(fromBlock = 2, toBlock = 4), null, 10).map { it.blockNumber })
            .containsExactly(4L, 3L, 2L)
        assertThat(adapter.scanDecoded(filter.copy(fromDate = t0.plusSeconds(2), toDate = t0.plusSeconds(3)), null, 10).map { it.blockNumber })
            .containsExactly(3L, 2L)
    }

    @Test
    fun `events with null blockTimestamp are left out by the date filter and counted by countDecodedWithoutTimestamp`() {
        adapter.saveAll(listOf(event(1), event(2, timestamp = null), event(3, timestamp = null), event(4), event(5, timestamp = null, address = tokenB)))

        val dated = adapter.scanDecoded(filter.copy(fromDate = t0, toDate = t0.plusSeconds(100)), null, 10)

        assertThat(dated.map { it.blockNumber }).containsExactly(4L, 1L)
        assertThat(adapter.countDecodedWithoutTimestamp(filter.copy(fromDate = t0, toDate = t0.plusSeconds(100)))).isEqualTo(2)
        assertThat(adapter.countDecodedWithoutTimestamp(filter.copy(fromBlock = 3))).isEqualTo(1)
    }
}
