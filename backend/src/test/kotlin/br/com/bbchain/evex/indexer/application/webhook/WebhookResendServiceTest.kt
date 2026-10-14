package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.HostResolverPort
import br.com.bbchain.evex.indexer.domain.webhook.InvalidWebhookRequestException
import br.com.bbchain.evex.indexer.domain.webhook.OutboundUrlPolicy
import br.com.bbchain.evex.indexer.domain.webhook.RateLimitExceededException
import br.com.bbchain.evex.indexer.domain.webhook.TooManyEventsException
import br.com.bbchain.evex.indexer.domain.webhook.UrlNotAllowedException
import br.com.bbchain.evex.indexer.domain.webhook.UrlOverrideDisabledException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryOrigin
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookInactiveException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Unit test do reenvio — portas MockK, fixtures neutras (TokenA/ValueChanged/`example.invalid`). */
class WebhookResendServiceTest {

    private val webhookConfigs: WebhookConfigPort = mockk()
    private val events: BlockchainEventPort = mockk()
    private val deliveries: WebhookDeliveryPort = mockk()
    private val matchingEvents: WebhookMatchingEventsService = mockk()
    private val entityModelRecords: EntityModelRecordPort = mockk()
    private val blockResolver: EnrichmentBlockResolver = mockk()
    private val properties = WebhookResendProperties()
    private val resolver = object : HostResolverPort {
        val table = mutableMapOf("example.invalid" to listOf("203.0.113.10"), "internal.invalid" to listOf("10.0.0.7"))
        override fun resolve(host: String): List<InetAddress> = table[host].orEmpty().map { InetAddress.getByName(it) }
    }
    private val composer = WebhookPayloadComposer(WebhookPayloadRenderer(), mockk(), entityModelRecords)

    private fun service(limiter: ResendRateLimiter = ResendRateLimiter(properties)) = WebhookResendService(
        webhookConfigs, events, deliveries, composer, matchingEvents, entityModelRecords, blockResolver, OutboundUrlPolicy(resolver), limiter, properties,
    )

    private val contract = "0x0000000000000000000000000000000000000001"
    private val webhookId = UUID.randomUUID()
    private val enqueued = mutableListOf<WebhookDelivery>()

    private fun config(active: Boolean = true) = WebhookConfig(
        id = webhookId, contractAddress = contract, network = Network.TESTNET, eventName = "ValueChanged",
        url = "http://sink.invalid/hook", filters = emptyList(), payloadTemplate = null, active = active, createdAt = Instant.now(),
    )

    private fun event(block: Long = 10, decoded: Boolean = true, eventName: String = "ValueChanged", contractAddress: String = contract) = BlockchainEvent(
        id = UUID.randomUUID(), contractAddress = contractAddress, network = Network.TESTNET, blockNumber = block, blockHash = "0xb", txHash = "0xtx$block",
        logIndex = 0, topic0 = "0xt0", rawTopics = listOf("0xt0"), rawData = "0x", decoded = decoded,
        eventName = if (decoded) eventName else null, decodedParameters = if (decoded) mapOf("value" to "1") else null,
        blockTimestamp = Instant.parse("2026-01-01T00:00:00Z"), createdAt = Instant.now(),
    )

    init {
        every { webhookConfigs.findById(webhookId) } returns config()
        every { deliveries.existsPendingResend(any(), any()) } returns false
        every { deliveries.countPendingResend() } returns 0
        every { deliveries.enqueueResendIfAbsent(any()) } answers { firstArg<WebhookDelivery>().also { enqueued += it } }
        every { blockResolver.resolve(any()) } answers {
            val spec = firstArg<EnrichmentBlockSpec>()
            spec to PreviewEnrichmentBlock(spec.mode, spec.blockNumber, null, null, null, emptyList())
        }
    }

    private fun stubEvents(vararg evs: BlockchainEvent) {
        every { events.findAllByIds(any()) } answers { firstArg<Collection<UUID>>().mapNotNull { id -> evs.find { it.id == id } } }
    }

    @Test
    fun `enqueues a RESEND delivery per matching event with the same enqueue as the normal dispatch`() {
        val e1 = event(10)
        val e2 = event(11)
        stubEvents(e1, e2)

        val outcome = service().resend(webhookId, listOf(e1.id, e2.id, e1.id), null)

        assertThat(outcome.accepted.map { it.eventId }).containsExactly(e1.id, e2.id) // deduplicado
        assertThat(outcome.skipped).isEmpty()
        assertThat(enqueued).hasSize(2)
        val d = enqueued.first()
        assertThat(d.origin).isEqualTo(WebhookDeliveryOrigin.RESEND)
        assertThat(d.resend).isTrue()
        assertThat(d.webhookConfigId).isEqualTo(webhookId)
        assertThat(d.status.name).isEqualTo("PENDING")
        assertThat(d.blockNumber).isEqualTo(10)
        assertThat(d.targetUrlOverride).isNull()
        assertThat(d.enrichmentBlockMode).isEqualTo("EVENT_BLOCK")
        assertThat(d.enrichmentBlockNumber).isNull()
        assertThat(outcome.accepted.map { it.deliveryId }).containsExactlyElementsOf(enqueued.map { it.id })
    }

    @Test
    fun `writes only through WebhookDeliveryPort enqueue - no event, balance or model write exists on the collaborators`() {
        val e1 = event()
        stubEvents(e1)

        service().resend(webhookId, listOf(e1.id), null)

        // events e entityModelRecords são mocks estritos (sem stub de updateAll/saveAll/mergeFields): qualquer escrita lançaria MockKException.
        verify(exactly = 1) { deliveries.enqueueResendIfAbsent(any()) }
        verify(exactly = 0) { events.updateAll(any()) }
        verify(exactly = 0) { events.saveAll(any()) }
    }

    @Test
    fun `skips with a reason - EVENT_NOT_FOUND, EVENT_NOT_DECODED, NOT_MATCHING and ALREADY_PENDING`() {
        val ok = event(10)
        val undecoded = event(11, decoded = false)
        val otherEvent = event(12, eventName = "OtherEvent")
        val otherContract = event(13, contractAddress = "0x0000000000000000000000000000000000000002")
        val pending = event(14)
        val missing = UUID.randomUUID()
        stubEvents(ok, undecoded, otherEvent, otherContract, pending)
        every { deliveries.existsPendingResend(webhookId, pending.id) } returns true

        val outcome = service().resend(webhookId, listOf(ok.id, undecoded.id, otherEvent.id, otherContract.id, pending.id, missing), null)

        assertThat(outcome.accepted.map { it.eventId }).containsExactly(ok.id)
        assertThat(outcome.skipped.associate { it.eventId to it.reason }).isEqualTo(
            mapOf(
                missing to "EVENT_NOT_FOUND", undecoded.id to "EVENT_NOT_DECODED", otherEvent.id to "NOT_MATCHING",
                otherContract.id to "NOT_MATCHING", pending.id to "ALREADY_PENDING",
            ),
        )
        assertThat(enqueued).hasSize(1)
    }

    @Test
    fun `eventIds XOR range - both or none is 400, empty list is 400`() {
        assertThrows<InvalidWebhookRequestException> { service().resend(webhookId, null, null) }
        assertThrows<InvalidWebhookRequestException> { service().resend(webhookId, listOf(UUID.randomUUID()), ResendRange()) }
        assertThrows<InvalidWebhookRequestException> { service().resend(webhookId, emptyList(), null) }
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `more eventIds than the cap is TooManyEvents and NOTHING is enqueued (51 vs 50)`() {
        val ids = (1..51).map { UUID.randomUUID() }

        val ex = assertThrows<TooManyEventsException> { service().resend(webhookId, ids, null) }

        assertThat(ex.limit).isEqualTo(50)
        assertThat(ex.requested).isEqualTo(51)
        assertThat(enqueued).isEmpty()
        verify(exactly = 0) { events.findAllByIds(any()) }
        // exatamente no teto passa
        stubEvents()
        val outcome = service().resend(webhookId, ids.take(50), null)
        assertThat(outcome.skipped).hasSize(50)
    }

    @Test
    fun `duplicated ids count once toward the cap`() {
        val id = UUID.randomUUID()
        stubEvents()
        val outcome = service().resend(webhookId, List(60) { id }, null)
        assertThat(outcome.skipped).hasSize(1)
    }

    @Test
    fun `range resolves matching events up to the cap and warns when truncated - never enqueues beyond the cap`() {
        properties.maxEventsPerRequest = 2
        val evs = listOf(event(12), event(11))
        every { matchingEvents.listMatchingEvents(webhookId, 5, 20, null, null, null, 2) } returns
            MatchingEventsPage(evs, nextCursor = "c", scanned = 2, scanTruncated = false, filtersApplied = false, warnings = listOf("DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP:3"))

        val outcome = service().resend(webhookId, null, ResendRange(fromBlock = 5, toBlock = 20))

        assertThat(outcome.accepted).hasSize(2)
        assertThat(outcome.warnings).contains("RANGE_TRUNCATED", "DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP:3")
        assertThat(enqueued).hasSize(2)
    }

    @Test
    fun `range with a truncated scan reports SCAN_TRUNCATED and a complete range has no truncation warning`() {
        every { matchingEvents.listMatchingEvents(any(), any(), any(), any(), any(), any(), any()) } returns
            MatchingEventsPage(emptyList(), "c", 5000, scanTruncated = true, filtersApplied = true, warnings = emptyList())
        assertThat(service().resend(webhookId, null, ResendRange()).warnings).containsExactly("SCAN_TRUNCATED")

        every { matchingEvents.listMatchingEvents(any(), any(), any(), any(), any(), any(), any()) } returns
            MatchingEventsPage(listOf(event()), null, 1, scanTruncated = false, filtersApplied = false, warnings = emptyList())
        assertThat(service().resend(webhookId, null, ResendRange()).warnings).isEmpty()
    }

    @Test
    fun `unknown webhook is 404`() {
        every { webhookConfigs.findById(any()) } returns null
        assertThrows<WebhookConfigNotFoundException> { service().resend(UUID.randomUUID(), listOf(UUID.randomUUID()), null) }
    }

    @Test
    fun `inactive webhook is WEBHOOK_INACTIVE - except with a valid override url`() {
        every { webhookConfigs.findById(webhookId) } returns config(active = false)
        val e1 = event()
        stubEvents(e1)

        assertThrows<WebhookInactiveException> { service().resend(webhookId, listOf(e1.id), null) }
        assertThat(enqueued).isEmpty()

        properties.allowUrlOverride = true
        val outcome = service().resend(webhookId, listOf(e1.id), null, overrideUrl = "https://example.invalid/test")
        assertThat(outcome.accepted).hasSize(1)

        // override inválido num webhook inativo continua 400 (não vira 409 nem passa)
        assertThrows<UrlNotAllowedException> { service().resend(webhookId, listOf(e1.id), null, overrideUrl = "http://127.0.0.1/") }
    }

    @Test
    fun `overrideUrl is rejected while the capability is off (default)`() {
        val e1 = event()
        stubEvents(e1)
        assertThat(properties.allowUrlOverride).isFalse()

        assertThrows<UrlOverrideDisabledException> { service().resend(webhookId, listOf(e1.id), null, overrideUrl = "https://example.invalid/test") }
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `with the capability on, the policy blocks internal destinations with a GENERIC message that echoes neither url nor ip`() {
        properties.allowUrlOverride = true
        val e1 = event()
        stubEvents(e1)
        for (url in listOf("http://127.0.0.1/x", "http://169.254.169.254/", "https://internal.invalid/secret?token=abc", "ftp://example.invalid/", "https://u:p@example.invalid/")) {
            val ex = assertThrows<UrlNotAllowedException> { service().resend(webhookId, listOf(e1.id), null, overrideUrl = url) }
            assertThat(ex.message).doesNotContain("127.0.0.1", "169.254", "internal.invalid", "10.0.0.7", "token", "ftp")
        }
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `a present-but-blank overrideUrl is a validation error even with the capability off or the webhook inactive`() {
        val e1 = event()
        stubEvents(e1)
        for (allow in listOf(true, false)) {
            properties.allowUrlOverride = allow
            for (active in listOf(true, false)) {
                every { webhookConfigs.findById(webhookId) } returns config(active = active)
                for (blank in listOf("", "   ", "\t")) {
                    assertThrows<InvalidWebhookRequestException> { service().resend(webhookId, listOf(e1.id), null, overrideUrl = blank) }
                }
            }
        }
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `an event that loses the enqueue race is skipped ALREADY_PENDING and the rest of the batch is still enqueued`() {
        val e1 = event(block = 1)
        val e2 = event(block = 2)
        stubEvents(e1, e2)
        every { deliveries.enqueueResendIfAbsent(match { it.blockchainEventId == e1.id }) } returns null

        val outcome = service().resend(webhookId, listOf(e1.id, e2.id), null)

        assertThat(outcome.accepted.map { it.eventId }).containsExactly(e2.id)
        assertThat(outcome.skipped).containsExactly(ResendSkipped(e1.id, WebhookResendSkipReasons.ALREADY_PENDING))
    }

    @Test
    fun `a valid override is stored COMPLETE on the delivery (the DTO is what redacts it)`() {
        properties.allowUrlOverride = true
        val e1 = event()
        stubEvents(e1)

        service().resend(webhookId, listOf(e1.id), null, overrideUrl = " https://example.invalid/test?k=v ")

        assertThat(enqueued.single().targetUrlOverride).isEqualTo("https://example.invalid/test?k=v")
    }

    @Test
    fun `block modes - BLOCK_NUMBER and BLOCK_AT_DATE are frozen as a number, EVENT_BLOCK and LATEST keep only the mode`() {
        val e1 = event()
        stubEvents(e1)
        val date = Instant.parse("2026-02-01T00:00:00Z")
        every { blockResolver.resolve(match { it.mode == "BLOCK_AT_DATE" }) } returns
            (EnrichmentBlockSpec("BLOCK_AT_DATE", 777, date) to PreviewEnrichmentBlock("BLOCK_AT_DATE", 777, date, true, "RPC", emptyList()))

        fun run(spec: EnrichmentBlockSpec): WebhookDelivery {
            enqueued.clear()
            service().resend(webhookId, listOf(e1.id), null, spec)
            return enqueued.single()
        }

        run(EnrichmentBlockSpec("EVENT_BLOCK")).let { assertThat(it.enrichmentBlockMode to it.enrichmentBlockNumber).isEqualTo("EVENT_BLOCK" to null) }
        run(EnrichmentBlockSpec("LATEST")).let { assertThat(it.enrichmentBlockMode to it.enrichmentBlockNumber).isEqualTo("LATEST" to null) }
        run(EnrichmentBlockSpec("BLOCK_NUMBER", 123)).let { assertThat(it.enrichmentBlockMode to it.enrichmentBlockNumber).isEqualTo("BLOCK_NUMBER" to 123L) }
        val frozen = run(EnrichmentBlockSpec("BLOCK_AT_DATE", blockDate = date))
        assertThat(frozen.enrichmentBlockMode to frozen.enrichmentBlockNumber).isEqualTo("BLOCK_AT_DATE" to 777L)
    }

    @Test
    fun `an invalid block mode propagates before anything is enqueued`() {
        every { blockResolver.resolve(any()) } throws br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentBlockSpecException("modo inválido")
        val e1 = event()
        stubEvents(e1)
        assertThrows<br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentBlockSpecException> { service().resend(webhookId, listOf(e1.id), null, EnrichmentBlockSpec("NOPE")) }
        assertThat(enqueued).isEmpty()
    }

    @Test
    fun `a full resend queue is RESEND_QUEUE_FULL with Retry-After, all-or-nothing`() {
        val e1 = event(10)
        val e2 = event(11)
        stubEvents(e1, e2)
        properties.maxPendingResends = 500
        properties.queueFullRetryAfterSeconds = 45
        every { deliveries.countPendingResend() } returns 499 // cabe 1, mas são 2 -> nada é enfileirado

        val ex = assertThrows<RateLimitExceededException> { service().resend(webhookId, listOf(e1.id, e2.id), null) }

        assertThat(ex.code).isEqualTo("RESEND_QUEUE_FULL")
        assertThat(ex.retryAfterSeconds).isEqualTo(45)
        assertThat(enqueued).isEmpty()
        // com exatamente a vaga restante passa
        assertThat(service().resend(webhookId, listOf(e1.id), null).accepted).hasSize(1)
    }

    @Test
    fun `the rate limiter is applied inside the application service (per-webhook limit)`() {
        properties.maxResendsPerMinutePerWebhook = 1
        val svc = service()
        val e1 = event()
        stubEvents(e1)

        svc.resend(webhookId, listOf(e1.id), null)
        val ex = assertThrows<RateLimitExceededException> { svc.resend(webhookId, listOf(e1.id), null) }

        assertThat(ex.code).isEqualTo("RATE_LIMITED")
        assertThat(ex.retryAfterSeconds).isGreaterThan(0)
    }
}
