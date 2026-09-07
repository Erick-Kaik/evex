package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test

/** Unit test — every collaborator mocked via MockK (DEC-009). */
class WebhookDispatchJobTest {

    private val deliveries: WebhookDeliveryPort = mockk()
    private val webhookConfigs: WebhookConfigPort = mockk()
    private val dispatchPort: WebhookDispatchPort = mockk()
    private val recorder: WebhookDeliveryRecorder = mockk(relaxed = true)
    private val properties = WebhookProperties().apply { dispatchBatchSize = 50 }

    // E19/DEC-082: por padrão devolve o payload sem alteração (equivalente a "sem enrichmentProfileIds",
    // regressão zero) — testes específicos de enriquecimento sobrescrevem este stub.
    private val enrichmentResolver: WebhookEnrichmentResolver = mockk()

    private val job = WebhookDispatchJob(deliveries, webhookConfigs, dispatchPort, recorder, properties, enrichmentResolver)

    init {
        every { enrichmentResolver.resolve(any(), any()) } answers { firstArg<WebhookDelivery>().payload }
    }

    private fun config(url: String = "https://example.test/hook") = WebhookConfig(
        id = UUID.randomUUID(),
        contractAddress = "0xaaaa000000000000000000000000000000aaaa",
        network = Network.TESTNET,
        eventName = "Transfer",
        url = url,
        filters = emptyList(),
        payloadTemplate = null,
        active = true,
        createdAt = Instant.now(),
    )

    private fun delivery(webhookConfigId: UUID) = WebhookDelivery(
        id = UUID.randomUUID(),
        webhookConfigId = webhookConfigId,
        blockchainEventId = UUID.randomUUID(),
        network = Network.TESTNET,
        payload = "{}",
        status = WebhookDeliveryStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `dispatch processes only the due deliveries returned by findDue`() {
        val cfg = config()
        val due = delivery(cfg.id)
        every { deliveries.findDue(50) } returns listOf(due)
        every { webhookConfigs.findById(cfg.id) } returns cfg
        every { dispatchPort.send(cfg.url, due.payload) } returns WebhookAttemptResult(success = true, httpStatus = 200, responseBody = null, errorMessage = null)

        job.dispatch()

        verify(exactly = 1) { deliveries.findDue(50) }
        verify(exactly = 1) { dispatchPort.send(cfg.url, due.payload) }
    }

    @Test
    fun `dispatch delegates the result to the recorder, called for the separate-bean transactional boundary`() {
        val cfg = config()
        val due = delivery(cfg.id)
        val result = WebhookAttemptResult(success = false, httpStatus = 500, responseBody = "err", errorMessage = null)
        every { deliveries.findDue(50) } returns listOf(due)
        every { webhookConfigs.findById(cfg.id) } returns cfg
        every { dispatchPort.send(cfg.url, due.payload) } returns result

        job.dispatch()

        verify(exactly = 1) { recorder.recordAttempt(due, result) }
    }

    @Test
    fun `dispatch skips a delivery whose webhookConfigId no longer resolves, without throwing`() {
        val due = delivery(UUID.randomUUID())
        every { deliveries.findDue(50) } returns listOf(due)
        every { webhookConfigs.findById(due.webhookConfigId) } returns null

        job.dispatch()

        verify(exactly = 0) { dispatchPort.send(any(), any()) }
        verify(exactly = 0) { recorder.recordAttempt(any(), any()) }
    }

    @Test
    fun `dispatch with no due deliveries does nothing`() {
        every { deliveries.findDue(50) } returns emptyList()

        job.dispatch()

        verify(exactly = 0) { dispatchPort.send(any(), any()) }
        verify(exactly = 0) { recorder.recordAttempt(any(), any()) }
    }

    @Test
    fun `dispatch isolates a failure recording 1 delivery - the other due deliveries in the same batch are still processed (E8-1)`() {
        val cfgA = config(url = "https://example.test/a")
        val cfgB = config(url = "https://example.test/b")
        val cfgC = config(url = "https://example.test/c")
        val deliveryA = delivery(cfgA.id)
        val deliveryB = delivery(cfgB.id)
        val deliveryC = delivery(cfgC.id)
        val result = WebhookAttemptResult(success = true, httpStatus = 200, responseBody = null, errorMessage = null)
        every { deliveries.findDue(50) } returns listOf(deliveryA, deliveryB, deliveryC)
        every { webhookConfigs.findById(cfgA.id) } returns cfgA
        every { webhookConfigs.findById(cfgB.id) } returns cfgB
        every { webhookConfigs.findById(cfgC.id) } returns cfgC
        every { dispatchPort.send(any(), any()) } returns result
        // simula uma falha futura em recorder.recordAttempt (ex.: erro ao gravar no banco) só pra
        // deliveryB — nunca deve abortar o processamento de deliveryC, que vem depois no mesmo lote.
        every { recorder.recordAttempt(deliveryB, result) } throws RuntimeException("boom")

        job.dispatch()

        verify(exactly = 1) { recorder.recordAttempt(deliveryA, result) }
        verify(exactly = 1) { recorder.recordAttempt(deliveryB, result) }
        verify(exactly = 1) { recorder.recordAttempt(deliveryC, result) }
        verify(exactly = 1) { dispatchPort.send(cfgC.url, deliveryC.payload) }
    }

    @Test
    fun `dispatch sends the payload resolved by WebhookEnrichmentResolver, not the raw stored payload`() {
        // E19/DEC-082 — Fase 2: a resolução acontece aqui, fora de transação, imediatamente antes do
        // POST; o `send` precisa receber o payload já com {{enriched.*}} substituído.
        val cfg = config().copy(enrichmentProfileIds = listOf(UUID.randomUUID()))
        val due = delivery(cfg.id).copy(payload = """{"amount":"{{enriched.total}}"}""")
        val resolvedPayload = """{"amount":"125000"}"""
        every { deliveries.findDue(50) } returns listOf(due)
        every { webhookConfigs.findById(cfg.id) } returns cfg
        every { enrichmentResolver.resolve(due, cfg.enrichmentProfileIds) } returns resolvedPayload
        every { dispatchPort.send(cfg.url, resolvedPayload) } returns WebhookAttemptResult(success = true, httpStatus = 200, responseBody = null, errorMessage = null)

        job.dispatch()

        verify(exactly = 1) { enrichmentResolver.resolve(due, cfg.enrichmentProfileIds) }
        verify(exactly = 1) { dispatchPort.send(cfg.url, resolvedPayload) }
        verify(exactly = 0) { dispatchPort.send(cfg.url, due.payload) }
    }
}
