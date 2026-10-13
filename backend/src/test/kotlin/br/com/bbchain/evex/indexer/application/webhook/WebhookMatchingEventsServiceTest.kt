package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventCursor
import br.com.bbchain.evex.indexer.domain.event.EventScanFilter
import br.com.bbchain.evex.indexer.domain.event.InvalidEventCursorException
import br.com.bbchain.evex.indexer.domain.webhook.FilterOperator
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit test de [WebhookMatchingEventsService]: porta de eventos SIMULADA em memória (mesma ordem
 * `block DESC, logIndex DESC` e semântica de cursor "estritamente antes" da implementação JPA, essa coberta
 * no teste de integração da API).
 */
class WebhookMatchingEventsServiceTest {

    private val contractAddress = "0x0000000000000000000000000000000000000001"
    private val webhookId = UUID.randomUUID()
    private val webhookConfigs: WebhookConfigPort = mockk()
    private val eventPort: BlockchainEventPort = mockk()
    private val properties = WebhookResendProperties()
    private val service = WebhookMatchingEventsService(webhookConfigs, eventPort, properties)

    private var store: List<BlockchainEvent> = emptyList()
    private var withoutTimestamp = 0L
    private val scanFilters = mutableListOf<EventScanFilter>()
    private val baseTs = Instant.parse("2026-01-01T00:00:00Z")

    private fun event(block: Long, value: Long, logIndex: Long = 0) = BlockchainEvent(
        id = UUID.randomUUID(), contractAddress = contractAddress, network = Network.TESTNET, blockNumber = block, blockHash = "0xb",
        txHash = "0xtx-$block-$logIndex", logIndex = logIndex, topic0 = "0xt0", rawTopics = listOf("0xt0"), rawData = "0x", decoded = true,
        eventName = "ValueChanged", decodedParameters = mapOf("value" to value.toString()), blockTimestamp = baseTs.plusSeconds(block * 10),
        createdAt = Instant.now(),
    )

    private fun config(filters: List<WebhookFilterCondition> = emptyList()) = WebhookConfig(
        webhookId, contractAddress, Network.TESTNET, "ValueChanged", "http://sink.invalid/hook", filters, null, true, Instant.now(),
    )

    private fun setUp(events: List<BlockchainEvent>, config: WebhookConfig = config()) {
        store = events
        every { webhookConfigs.findById(webhookId) } returns config
        every { eventPort.scanDecoded(any(), any(), any()) } answers {
            val filter = firstArg<EventScanFilter>()
            val after = secondArg<EventCursor?>()
            val limit = thirdArg<Int>()
            scanFilters += filter
            store.asSequence()
                .filter { filter.fromBlock == null || it.blockNumber >= filter.fromBlock!! }
                .filter { filter.toBlock == null || it.blockNumber <= filter.toBlock!! }
                .filter { filter.fromDate == null || (it.blockTimestamp != null && !it.blockTimestamp!!.isBefore(filter.fromDate)) }
                .filter { filter.toDate == null || (it.blockTimestamp != null && !it.blockTimestamp!!.isAfter(filter.toDate)) }
                .filter { after == null || it.blockNumber < after.blockNumber || (it.blockNumber == after.blockNumber && it.logIndex < after.logIndex) }
                .sortedWith(compareByDescending<BlockchainEvent> { it.blockNumber }.thenByDescending { it.logIndex })
                .take(limit)
                .toList()
        }
        every { eventPort.countDecodedWithoutTimestamp(any()) } answers { withoutTimestamp }
    }

    private fun list(
        fromBlock: Long? = null, toBlock: Long? = null, fromDate: Instant? = null, toDate: Instant? = null, cursor: String? = null, size: Int = 20,
    ) = service.listMatchingEvents(webhookId, fromBlock, toBlock, fromDate, toDate, cursor, size)

    @Test
    fun `unknown webhook is a 404-style exception`() {
        every { webhookConfigs.findById(webhookId) } returns null
        assertThatThrownBy { list() }.isInstanceOf(WebhookConfigNotFoundException::class.java)
    }

    @Test
    fun `scan filter carries contract, event and network of the webhook, plus the window`() {
        setUp(emptyList())
        val slot = slot<EventScanFilter>()
        every { eventPort.scanDecoded(capture(slot), any(), any()) } returns emptyList()

        list(fromBlock = 5, toBlock = 9)

        assertThat(slot.captured).isEqualTo(EventScanFilter(contractAddress, "ValueChanged", Network.TESTNET, fromBlock = 5, toBlock = 9))
    }

    @Test
    fun `only events that satisfy the webhook filters are returned, most recent first`() {
        setUp(
            listOf(event(1, 10), event(2, 2000), event(3, 5), event(4, 3000)),
            config(listOf(WebhookFilterCondition("value", FilterOperator.GT, "1000"))),
        )

        val page = list()

        assertThat(page.events.map { it.blockNumber }).containsExactly(4, 2)
        assertThat(page.scanned).isEqualTo(4)
        assertThat(page.scanTruncated).isFalse()
        assertThat(page.nextCursor).isNull()
        assertThat(page.filtersApplied).isTrue()
    }

    @Test
    fun `webhook without filters reports filtersApplied=false and scanned equals the page size`() {
        setUp((1L..10L).map { event(it, it) })

        val page = list(size = 3)

        assertThat(page.events).hasSize(3)
        assertThat(page.scanned).isEqualTo(3)
        assertThat(page.filtersApplied).isFalse()
        assertThat(page.nextCursor).isEqualTo("8:0")
    }

    @Test
    fun `cursor pagination never repeats nor skips an event`() {
        val all = (1L..23L).map { event(it, it) } // alguns no mesmo bloco abaixo
        val sameBlock = listOf(event(30, 1, logIndex = 0), event(30, 2, logIndex = 1), event(30, 3, logIndex = 2))
        setUp(all + sameBlock, config(listOf(WebhookFilterCondition("value", FilterOperator.GTE, "1"))))

        val seen = mutableListOf<UUID>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = list(cursor = cursor, size = 4)
            seen += page.events.map { it.id }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 20)

        assertThat(seen).hasSize(26).doesNotHaveDuplicates()
        assertThat(seen).containsExactlyInAnyOrderElementsOf((all + sameBlock).map { it.id })
    }

    @Test
    fun `scan is truncated at max-scan with few matches and the returned cursor resumes the scan`() {
        properties.maxScan = 10
        // 25 eventos; só o de bloco 1 casa (value > 1000)
        val events = (1L..25L).map { event(it, if (it == 1L) 5000 else 1) }
        setUp(events, config(listOf(WebhookFilterCondition("value", FilterOperator.GT, "1000"))))

        val first = list(size = 5)

        assertThat(first.scanTruncated).isTrue()
        assertThat(first.scanned).isEqualTo(10)
        assertThat(first.events).isEmpty()
        assertThat(first.nextCursor).isEqualTo("16:0") // último varrido: blocos 25..16

        val second = list(cursor = first.nextCursor, size = 5)
        assertThat(second.scanned).isEqualTo(10) // 15..6
        assertThat(second.scanTruncated).isTrue()
        val third = list(cursor = second.nextCursor, size = 5)
        assertThat(third.events.map { it.blockNumber }).containsExactly(1)
        assertThat(third.scanTruncated).isFalse()
        assertThat(third.nextCursor).isNull()
    }

    @Test
    fun `date window warns about events without timestamp, which a date filter ignores`() {
        setUp((1L..5L).map { event(it, it) })
        withoutTimestamp = 3

        val page = list(fromDate = baseTs, toDate = baseTs.plusSeconds(35))

        assertThat(page.events.map { it.blockNumber }).containsExactly(3, 2, 1)
        assertThat(page.warnings).containsExactly("DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP:3")
    }

    @Test
    fun `no date window means no warning and no extra count query`() {
        setUp((1L..3L).map { event(it, it) })
        withoutTimestamp = 3

        assertThat(list(fromBlock = 1).warnings).isEmpty()
        verify(exactly = 0) { eventPort.countDecodedWithoutTimestamp(any()) }
    }

    @Test
    fun `invalid cursor fails with InvalidEventCursorException`() {
        setUp(emptyList())
        assertThatThrownBy { list(cursor = "not-a-cursor") }.isInstanceOf(InvalidEventCursorException::class.java)
        assertThatThrownBy { list(cursor = "1:x") }.isInstanceOf(InvalidEventCursorException::class.java)
    }

    @Test
    fun `a filter that throws does not match instead of failing the page`() {
        // parâmetro ausente: GT sobre não-numérico apenas não casa; aqui garantimos que nada propaga
        setUp(listOf(event(1, 1).copy(decodedParameters = null)), config(listOf(WebhookFilterCondition("value", FilterOperator.GT, "1"))))
        assertThat(list().events).isEmpty()
    }
}
