package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises
 * [JpaBlockchainEventAdapter] through the Spring Data repository it wraps. Same pattern as
 * [JpaContractAbiAdapterTest].
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaBlockchainEventAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: BlockchainEventJpaRepository

    private val adapter: JpaBlockchainEventAdapter by lazy { JpaBlockchainEventAdapter(repository) }

    @Test
    fun `saveAll persists events and existsByTxHashAndLogIndex confirms them`() {
        val event = newEvent(blockNumber = 10, txHash = "0xabc")

        adapter.saveAll(listOf(event))

        assertThat(adapter.existsByTxHashAndLogIndex("0xabc", 0)).isTrue()
        assertThat(adapter.existsByTxHashAndLogIndex("0xother", 0)).isFalse()
    }

    @Test
    fun `saveAll round-trips rawTopics and decodedParameters through JSONB`() {
        val event = newEvent(
            blockNumber = 10,
            txHash = "0xdecoded",
            decoded = true,
            eventName = "Transfer",
            decodedParameters = mapOf("from" to "0xabc", "value" to "100"),
        )

        adapter.saveAll(listOf(event))
        val page = adapter.search(EventSearchFilter(eventName = "Transfer"), page = 0, size = 10)

        val found = page.content.single()
        assertThat(found.rawTopics).isEqualTo(event.rawTopics)
        assertThat(found.decodedParameters).isEqualTo(event.decodedParameters)
    }

    @Test
    fun `search filters by contractAddress`() {
        adapter.saveAll(listOf(newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 1, txHash = "0x1")))
        adapter.saveAll(listOf(newEvent(address = "0x222222222222222222222222222222222222222b", blockNumber = 2, txHash = "0x2")))

        val page = adapter.search(EventSearchFilter(contractAddress = "0x111111111111111111111111111111111111111a"), 0, 10)

        assertThat(page.content).hasSize(1)
        assertThat(page.content.single().contractAddress).isEqualTo("0x111111111111111111111111111111111111111a")
    }

    @Test
    fun `search filters by eventName`() {
        adapter.saveAll(listOf(newEvent(blockNumber = 1, txHash = "0x1", decoded = true, eventName = "Transfer")))
        adapter.saveAll(listOf(newEvent(blockNumber = 2, txHash = "0x2", decoded = true, eventName = "Approval")))

        val page = adapter.search(EventSearchFilter(eventName = "Approval"), 0, 10)

        assertThat(page.content).hasSize(1)
        assertThat(page.content.single().eventName).isEqualTo("Approval")
    }

    @Test
    fun `search filters by fromBlock and toBlock`() {
        adapter.saveAll(listOf(newEvent(blockNumber = 100, txHash = "0x1")))
        adapter.saveAll(listOf(newEvent(blockNumber = 200, txHash = "0x2")))
        adapter.saveAll(listOf(newEvent(blockNumber = 300, txHash = "0x3")))

        val page = adapter.search(EventSearchFilter(fromBlock = 150, toBlock = 250), 0, 10)

        assertThat(page.content.map { it.blockNumber }).containsExactly(200L)
    }

    @Test
    fun `search filters by fromDate and toDate on blockTimestamp`() {
        val early = Instant.parse("2026-01-01T00:00:00Z")
        val mid = Instant.parse("2026-06-01T00:00:00Z")
        val late = Instant.parse("2026-12-01T00:00:00Z")
        adapter.saveAll(listOf(newEvent(blockNumber = 1, txHash = "0x1", blockTimestamp = early)))
        adapter.saveAll(listOf(newEvent(blockNumber = 2, txHash = "0x2", blockTimestamp = mid)))
        adapter.saveAll(listOf(newEvent(blockNumber = 3, txHash = "0x3", blockTimestamp = late)))

        val page = adapter.search(
            EventSearchFilter(fromDate = Instant.parse("2026-03-01T00:00:00Z"), toDate = Instant.parse("2026-09-01T00:00:00Z")),
            0,
            10,
        )

        assertThat(page.content).hasSize(1)
        assertThat(page.content.single().blockTimestamp).isEqualTo(mid)
    }

    @Test
    fun `search combines contractAddress and eventName filters`() {
        adapter.saveAll(
            listOf(
                newEvent(
                    address = "0x111111111111111111111111111111111111111a",
                    blockNumber = 10,
                    txHash = "0x1",
                    decoded = true,
                    eventName = "Transfer",
                ),
            ),
        )
        adapter.saveAll(
            listOf(
                newEvent(
                    address = "0x111111111111111111111111111111111111111a",
                    blockNumber = 20,
                    txHash = "0x2",
                    decoded = true,
                    eventName = "Approval",
                ),
            ),
        )
        adapter.saveAll(
            listOf(
                newEvent(
                    address = "0x222222222222222222222222222222222222222b",
                    blockNumber = 10,
                    txHash = "0x3",
                    decoded = true,
                    eventName = "Transfer",
                ),
            ),
        )

        val page = adapter.search(
            EventSearchFilter(contractAddress = "0x111111111111111111111111111111111111111a", eventName = "Transfer"),
            0,
            10,
        )

        assertThat(page.content).hasSize(1)
        assertThat(page.content.single().txHash).isEqualTo("0x1")
    }

    @Test
    fun `search without filters paginates all events`() {
        (1..3L).forEach { adapter.saveAll(listOf(newEvent(blockNumber = it, txHash = "0xpage$it"))) }

        val page = adapter.search(EventSearchFilter(), page = 0, size = 2)

        assertThat(page.content).hasSize(2)
        assertThat(page.totalElements).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `findPendingCandidates returns only decoded=false rows matching the contractAddress filter`() {
        adapter.saveAll(
            listOf(
                newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 1, txHash = "0xp1"),
                newEvent(address = "0x222222222222222222222222222222222222222b", blockNumber = 2, txHash = "0xp2"),
                newEvent(
                    address = "0x111111111111111111111111111111111111111a",
                    blockNumber = 3,
                    txHash = "0xp3",
                    decoded = true,
                    eventName = "Transfer",
                ),
            ),
        )

        val candidates = adapter.findPendingCandidates(
            RedecodeFilter(contractAddress = "0x111111111111111111111111111111111111111a"),
            limit = 10,
        )

        assertThat(candidates.map { it.txHash }).containsExactly("0xp1")
    }

    @Test
    fun `findPendingCandidates returns only decoded=false rows matching the topic0 filter across multiple contracts`() {
        adapter.saveAll(
            listOf(
                newEvent(
                    address = "0x111111111111111111111111111111111111111a",
                    blockNumber = 1,
                    txHash = "0xt1",
                    topic0 = "0xshared",
                ),
                newEvent(
                    address = "0x222222222222222222222222222222222222222b",
                    blockNumber = 2,
                    txHash = "0xt2",
                    topic0 = "0xshared",
                ),
                newEvent(
                    address = "0x333333333333333333333333333333333333333c",
                    blockNumber = 3,
                    txHash = "0xt3",
                    topic0 = "0xother",
                ),
            ),
        )

        val candidates = adapter.findPendingCandidates(RedecodeFilter(topic0 = "0xshared"), limit = 10)

        assertThat(candidates.map { it.txHash }).containsExactlyInAnyOrder("0xt1", "0xt2")
    }

    @Test
    fun `findPendingCandidates limits by block window`() {
        adapter.saveAll(
            listOf(
                newEvent(blockNumber = 100, txHash = "0xw1"),
                newEvent(blockNumber = 200, txHash = "0xw2"),
                newEvent(blockNumber = 300, txHash = "0xw3"),
            ),
        )

        val candidates = adapter.findPendingCandidates(RedecodeFilter(fromBlock = 150, toBlock = 250), limit = 10)

        assertThat(candidates.map { it.blockNumber }).containsExactly(200L)
    }

    @Test
    fun `findPendingCandidates combines contractAddress and block window filters`() {
        adapter.saveAll(
            listOf(
                newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 100, txHash = "0xc1"),
                newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 900, txHash = "0xc2"),
                newEvent(address = "0x222222222222222222222222222222222222222b", blockNumber = 100, txHash = "0xc3"),
            ),
        )

        val candidates = adapter.findPendingCandidates(
            RedecodeFilter(contractAddress = "0x111111111111111111111111111111111111111a", fromBlock = 0, toBlock = 500),
            limit = 10,
        )

        assertThat(candidates.map { it.txHash }).containsExactly("0xc1")
    }

    @Test
    fun `findPendingCandidates orders stably by blockNumber, logIndex then id across successive calls`() {
        adapter.saveAll(
            listOf(
                newEvent(blockNumber = 2, logIndex = 0, txHash = "0xo1"),
                newEvent(blockNumber = 1, logIndex = 1, txHash = "0xo2"),
                newEvent(blockNumber = 1, logIndex = 0, txHash = "0xo3"),
            ),
        )

        val firstCall = adapter.findPendingCandidates(RedecodeFilter(), limit = 10)
        val secondCall = adapter.findPendingCandidates(RedecodeFilter(), limit = 10)

        assertThat(firstCall.map { it.txHash }).containsExactly("0xo3", "0xo2", "0xo1")
        assertThat(secondCall.map { it.txHash }).isEqualTo(firstCall.map { it.txHash })
    }

    @Test
    fun `countPending counts only decoded=false rows matching the filter`() {
        adapter.saveAll(
            listOf(
                newEvent(blockNumber = 1, txHash = "0xn1"),
                newEvent(blockNumber = 2, txHash = "0xn2"),
                newEvent(blockNumber = 3, txHash = "0xn3", decoded = true, eventName = "Transfer"),
            ),
        )

        assertThat(adapter.countPending(RedecodeFilter())).isGreaterThanOrEqualTo(2L)
    }

    @Test
    fun `updateAll updates existing rows in place, does not duplicate the primary key`() {
        val event = newEvent(blockNumber = 1, txHash = "0xu1")
        adapter.saveAll(listOf(event))

        val decoded = event.copy(decoded = true, eventName = "Transfer", decodedParameters = mapOf("value" to "1"))
        adapter.updateAll(listOf(decoded))

        val all = repository.findAll()
        val matching = all.filter { it.txHash == "0xu1" }
        assertThat(matching).hasSize(1)
        assertThat(matching.single().getId()).isEqualTo(event.id)
        assertThat(matching.single().decoded).isTrue()
        assertThat(matching.single().eventName).isEqualTo("Transfer")
    }

    @Test
    fun `findDistinctTopic0s dedups topic0s of multiple events of the same contract`() {
        adapter.saveAll(
            listOf(
                newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 1, txHash = "0xd1", topic0 = "0xtransfer"),
                newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 2, txHash = "0xd2", topic0 = "0xtransfer"),
                newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 3, txHash = "0xd3", topic0 = "0xapproval"),
                newEvent(address = "0x222222222222222222222222222222222222222b", blockNumber = 4, txHash = "0xd4", topic0 = "0xunrelated"),
            ),
        )

        val topics = adapter.findDistinctTopic0s("0x111111111111111111111111111111111111111a")

        assertThat(topics).containsExactlyInAnyOrder("0xtransfer", "0xapproval")
    }

    @Test
    fun `findDecodedForBackfill returns only decoded rows of the given contract-network-eventName, up to the watermark, ordered by block then logIndex`() {
        val addr = "0x444444444444444444444444444444444444444d"
        adapter.saveAll(
            listOf(
                newEvent(address = addr, blockNumber = 5, logIndex = 1, txHash = "0xb1", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 5, logIndex = 0, txHash = "0xb2", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 8, logIndex = 0, txHash = "0xb3", decoded = true, eventName = "Transfer"),
                // acima da marca d'água — não entra
                newEvent(address = addr, blockNumber = 20, logIndex = 0, txHash = "0xb4", decoded = true, eventName = "Transfer"),
                // eventName diferente — não entra
                newEvent(address = addr, blockNumber = 6, logIndex = 0, txHash = "0xb5", decoded = true, eventName = "Approval"),
                // ainda raw — não entra
                newEvent(address = addr, blockNumber = 7, logIndex = 0, txHash = "0xb6", decoded = false),
                // outro contrato — não entra
                newEvent(address = "0x555555555555555555555555555555555555555e", blockNumber = 6, logIndex = 0, txHash = "0xb7", decoded = true, eventName = "Transfer"),
            ),
        )

        val page = adapter.findDecodedForBackfill(addr, Network.TESTNET, "Transfer", afterBlock = -1, afterLogIndex = -1, maxBlock = 10, limit = 50)

        assertThat(page.map { it.txHash }).containsExactly("0xb2", "0xb1", "0xb3")
    }

    @Test
    fun `findDecodedForBackfill resumes strictly after the tuple cursor (block, logIndex)`() {
        val addr = "0x666666666666666666666666666666666666666f"
        adapter.saveAll(
            listOf(
                newEvent(address = addr, blockNumber = 5, logIndex = 0, txHash = "0xc1", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 5, logIndex = 1, txHash = "0xc2", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 5, logIndex = 2, txHash = "0xc3", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 9, logIndex = 0, txHash = "0xc4", decoded = true, eventName = "Transfer"),
            ),
        )

        val page = adapter.findDecodedForBackfill(addr, Network.TESTNET, "Transfer", afterBlock = 5, afterLogIndex = 1, maxBlock = 100, limit = 50)

        assertThat(page.map { it.txHash }).containsExactly("0xc3", "0xc4")
    }

    @Test
    fun `findDecodedForBackfill honors the batch limit`() {
        val addr = "0x777777777777777777777777777777777777777a"
        adapter.saveAll(
            (1..5L).map { newEvent(address = addr, blockNumber = it, logIndex = 0, txHash = "0xd$it", decoded = true, eventName = "Transfer") },
        )

        val page = adapter.findDecodedForBackfill(addr, Network.TESTNET, "Transfer", afterBlock = -1, afterLogIndex = -1, maxBlock = 100, limit = 2)

        assertThat(page.map { it.blockNumber }).containsExactly(1L, 2L)
    }

    @Test
    fun `countDecodedForBackfill counts the same predicate as findDecodedForBackfill`() {
        val addr = "0x888888888888888888888888888888888888888b"
        adapter.saveAll(
            listOf(
                newEvent(address = addr, blockNumber = 3, logIndex = 0, txHash = "0xe1", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 4, logIndex = 0, txHash = "0xe2", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 50, logIndex = 0, txHash = "0xe3", decoded = true, eventName = "Transfer"),
                newEvent(address = addr, blockNumber = 4, logIndex = 0, txHash = "0xe4", decoded = false),
            ),
        )

        assertThat(adapter.countDecodedForBackfill(addr, Network.TESTNET, "Transfer", afterBlock = -1, afterLogIndex = -1, maxBlock = 10)).isEqualTo(2L)
        assertThat(adapter.countDecodedForBackfill(addr, Network.TESTNET, "Transfer", afterBlock = 3, afterLogIndex = 0, maxBlock = 10)).isEqualTo(1L)
    }

    @Test
    fun `findDistinctTopic0s returns an empty set for a contract with no ingested events`() {
        val topics = adapter.findDistinctTopic0s("0x999999999999999999999999999999999999999f")

        assertThat(topics).isEmpty()
    }

    private fun newEvent(
        address: String = "0x111111111111111111111111111111111111111a",
        network: Network = Network.TESTNET,
        blockNumber: Long,
        logIndex: Long = 0,
        txHash: String,
        topic0: String = "0xtopic0",
        decoded: Boolean = false,
        eventName: String? = null,
        decodedParameters: Map<String, Any?>? = null,
        blockTimestamp: Instant? = null,
    ) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = network,
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = txHash,
        logIndex = logIndex,
        topic0 = topic0,
        rawTopics = listOf(topic0),
        rawData = "0xdata",
        decoded = decoded,
        eventName = eventName,
        decodedParameters = decodedParameters,
        // truncado pra microssegundos: coluna `timestamp` do Postgres não preserva nanossegundos.
        blockTimestamp = blockTimestamp?.truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )
}
