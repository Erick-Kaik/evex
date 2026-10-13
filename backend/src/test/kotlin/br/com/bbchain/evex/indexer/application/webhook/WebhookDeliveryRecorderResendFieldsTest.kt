package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** E33: parâmetros OPCIONAIS de `recordAttempt` (resultado de enriquecimento por tentativa). */
class WebhookDeliveryRecorderResendFieldsTest {

    private val deliveries: WebhookDeliveryPort = mockk()
    private val recorder = WebhookDeliveryRecorder(deliveries, WebhookProperties().apply { maxAttempts = 3 })

    private val delivery = WebhookDelivery(
        id = UUID.randomUUID(),
        webhookConfigId = UUID.randomUUID(),
        blockchainEventId = UUID.randomUUID(),
        network = Network.TESTNET,
        payload = "{}",
        status = WebhookDeliveryStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = null,
        createdAt = Instant.now(),
    )
    private val ok = WebhookAttemptResult(success = true, httpStatus = 200, responseBody = "ok", errorMessage = null)

    private fun captureAttempt(block: () -> Unit): WebhookDeliveryAttempt {
        val attempt = slot<WebhookDeliveryAttempt>()
        every { deliveries.recordAttempt(capture(attempt)) } answers { firstArg() }
        every { deliveries.save(any()) } answers { firstArg() }
        block()
        return attempt.captured
    }

    @Test
    fun `without the new parameters the attempt keeps them null (previous behaviour)`() {
        val attempt = captureAttempt { recorder.recordAttempt(delivery, ok) }

        assertThat(attempt.enrichmentResults).isNull()
        assertThat(attempt.enrichmentBlockUsed).isNull()
        assertThat(attempt.sentPayload).isNull()
        assertThat(attempt.success).isTrue()
        assertThat(attempt.attemptNumber).isEqualTo(1)
    }

    @Test
    fun `with the new parameters they are stored on the attempt and the delivery transition is unchanged`() {
        val results = listOf(
            EnrichmentCallReport(UUID.randomUUID(), "0x0000000000000000000000000000000000000001", "balanceOf", "OK", outputs = mapOf("saldo" to "1"), blockUsed = "7"),
        )
        val saved = slot<WebhookDelivery>()
        val attempt = captureAttempt {
            every { deliveries.save(capture(saved)) } answers { firstArg() }
            recorder.recordAttempt(delivery, ok, enrichmentResults = results, enrichmentBlockUsed = "7", sentPayload = "{\"x\":1}")
        }

        assertThat(attempt.enrichmentResults).isEqualTo(results)
        assertThat(attempt.enrichmentBlockUsed).isEqualTo("7")
        assertThat(attempt.sentPayload).isEqualTo("{\"x\":1}")
        assertThat(saved.captured.status).isEqualTo(WebhookDeliveryStatus.DELIVERED)
    }
}
