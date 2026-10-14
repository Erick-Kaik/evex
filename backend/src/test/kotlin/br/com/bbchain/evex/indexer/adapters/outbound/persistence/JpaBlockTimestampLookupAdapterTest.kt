package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
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
 * [JpaBlockTimestampLookupAdapter] contra Postgres real (Testcontainers). O `@BeforeEach` limpa a tabela
 * DENTRO da transação do teste (rollback ao final) para que dados commitados por outras classes não
 * interfiram nos casos de borda ("fora do intervalo").
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaBlockTimestampLookupAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: BlockchainEventJpaRepository

    private val events: JpaBlockchainEventAdapter by lazy { JpaBlockchainEventAdapter(repository) }
    private val lookup: JpaBlockTimestampLookupAdapter by lazy { JpaBlockTimestampLookupAdapter(repository) }

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeEach
    fun clean() {
        repository.deleteAllInBatch()
    }

    private fun event(block: Long, timestamp: Instant?, logIndex: Long = 0) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = "0x0000000000000000000000000000000000000001",
        network = Network.TESTNET,
        blockNumber = block,
        blockHash = "0xblock$block",
        txHash = "0xtx-$block-$logIndex-${UUID.randomUUID()}",
        logIndex = logIndex,
        topic0 = "0xtopic0",
        rawTopics = listOf("0xtopic0"),
        rawData = "0x",
        decoded = false,
        eventName = null,
        decodedParameters = null,
        blockTimestamp = timestamp?.truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `date between two ingested blocks yields lower (floor) and upper (ceiling)`() {
        events.saveAll(listOf(event(10, t0), event(20, t0.plusSeconds(100)), event(30, t0.plusSeconds(200))))

        val bracket = lookup.findBlockBracket(t0.plusSeconds(150))

        assertThat(bracket.lower?.blockNumber).isEqualTo(20)
        assertThat(bracket.lower?.blockTimestamp).isEqualTo(t0.plusSeconds(100))
        assertThat(bracket.upper?.blockNumber).isEqualTo(30)
        assertThat(bracket.upper?.blockTimestamp).isEqualTo(t0.plusSeconds(200))
    }

    @Test
    fun `an exact timestamp match appears on both sides`() {
        events.saveAll(listOf(event(10, t0), event(20, t0.plusSeconds(100)), event(30, t0.plusSeconds(200))))

        val bracket = lookup.findBlockBracket(t0.plusSeconds(100))

        assertThat(bracket.lower?.blockNumber).isEqualTo(20)
        assertThat(bracket.upper?.blockNumber).isEqualTo(20)
    }

    @Test
    fun `ties on the same timestamp - lower takes the highest block, upper takes the lowest block`() {
        val tie = t0.plusSeconds(100)
        events.saveAll(listOf(event(21, tie), event(20, tie), event(22, tie), event(5, t0), event(40, t0.plusSeconds(500))))

        assertThat(lookup.findBlockBracket(tie).lower?.blockNumber).isEqualTo(22)
        assertThat(lookup.findBlockBracket(tie).upper?.blockNumber).isEqualTo(20)
        // data logo antes/depois do empate: o empate inteiro é o vizinho
        assertThat(lookup.findBlockBracket(tie.plusSeconds(1)).lower?.blockNumber).isEqualTo(22)
        assertThat(lookup.findBlockBracket(tie.minusSeconds(1)).upper?.blockNumber).isEqualTo(20)
    }

    @Test
    fun `events with null blockTimestamp are ignored`() {
        events.saveAll(listOf(event(10, t0), event(15, null), event(20, null), event(30, t0.plusSeconds(200))))

        val bracket = lookup.findBlockBracket(t0.plusSeconds(100))

        assertThat(bracket.lower?.blockNumber).isEqualTo(10)
        assertThat(bracket.upper?.blockNumber).isEqualTo(30)
    }

    @Test
    fun `a date outside the ingested range yields a null side`() {
        events.saveAll(listOf(event(10, t0.plusSeconds(100)), event(20, t0.plusSeconds(200))))

        val before = lookup.findBlockBracket(t0)
        assertThat(before.lower).isNull()
        assertThat(before.upper?.blockNumber).isEqualTo(10)

        val after = lookup.findBlockBracket(t0.plusSeconds(1000))
        assertThat(after.lower?.blockNumber).isEqualTo(20)
        assertThat(after.upper).isNull()
    }

    @Test
    fun `no ingested events with a timestamp yields an empty bracket`() {
        events.saveAll(listOf(event(10, null)))

        val bracket = lookup.findBlockBracket(t0)

        assertThat(bracket.lower).isNull()
        assertThat(bracket.upper).isNull()
    }
}
