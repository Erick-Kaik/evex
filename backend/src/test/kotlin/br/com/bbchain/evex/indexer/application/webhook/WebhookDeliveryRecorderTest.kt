package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test — [WebhookDeliveryPort] mocked via MockK (DEC-009). */
class WebhookDeliveryRecorderTest {

    private val deliveries: WebhookDeliveryPort = mockk()
    private val properties = WebhookProperties().apply {
        maxAttempts = 3
        baseDelaySeconds = 10
        maxDelaySeconds = 100
    }
    private val recorder = WebhookDeliveryRecorder(deliveries, properties)

    private fun pendingDelivery(attemptCount: Int = 0) = WebhookDelivery(
        id = UUID.randomUUID(),
        webhookConfigId = UUID.randomUUID(),
        blockchainEventId = UUID.randomUUID(),
        network = Network.TESTNET,
        payload = "{}",
        status = WebhookDeliveryStatus.PENDING,
        attemptCount = attemptCount,
        nextAttemptAt = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `a successful attempt moves the delivery to DELIVERED and records the attempt`() {
        val delivery = pendingDelivery()
        every { deliveries.recordAttempt(any()) } answers { firstArg() }
        val savedSlot = slot<WebhookDelivery>()
        every { deliveries.save(capture(savedSlot)) } answers { savedSlot.captured }

        recorder.recordAttempt(delivery, WebhookAttemptResult(success = true, httpStatus = 200, responseBody = "ok", errorMessage = null))

        assertThat(savedSlot.captured.status).isEqualTo(WebhookDeliveryStatus.DELIVERED)
        assertThat(savedSlot.captured.attemptCount).isEqualTo(1)
        assertThat(savedSlot.captured.nextAttemptAt).isNull()
        val attemptSlot = slot<WebhookDeliveryAttempt>()
        verify(exactly = 1) { deliveries.recordAttempt(capture(attemptSlot)) }
        assertThat(attemptSlot.captured.success).isTrue()
        assertThat(attemptSlot.captured.attemptNumber).isEqualTo(1)
        assertThat(attemptSlot.captured.httpStatus).isEqualTo(200)
    }

    @Test
    fun `a failed attempt below maxAttempts stays PENDING with nextAttemptAt computed by the backoff formula`() {
        val delivery = pendingDelivery(attemptCount = 0)
        every { deliveries.recordAttempt(any()) } answers { firstArg() }
        val savedSlot = slot<WebhookDelivery>()
        every { deliveries.save(capture(savedSlot)) } answers { savedSlot.captured }
        val before = Instant.now()

        recorder.recordAttempt(delivery, WebhookAttemptResult(success = false, httpStatus = 500, responseBody = null, errorMessage = null))

        assertThat(savedSlot.captured.status).isEqualTo(WebhookDeliveryStatus.PENDING)
        assertThat(savedSlot.captured.attemptCount).isEqualTo(1)
        // backoff = min(baseDelaySeconds * 2^(1-1), maxDelaySeconds) = min(10 * 1, 100) = 10s
        assertThat(savedSlot.captured.nextAttemptAt).isAfter(before.plusSeconds(9))
        assertThat(savedSlot.captured.nextAttemptAt).isBefore(before.plusSeconds(12))
    }

    @Test
    fun `backoff doubles with each attempt and is capped at maxDelaySeconds`() {
        val delivery = pendingDelivery(attemptCount = 2) // about to become the 3rd attempt
        every { deliveries.recordAttempt(any()) } answers { firstArg() }
        val savedSlot = slot<WebhookDelivery>()
        every { deliveries.save(capture(savedSlot)) } answers { savedSlot.captured }
        val propertiesWithHighMax = WebhookProperties().apply { maxAttempts = 10; baseDelaySeconds = 10; maxDelaySeconds = 1000 }
        val recorderWithHighMax = WebhookDeliveryRecorder(deliveries, propertiesWithHighMax)
        val before = Instant.now()

        recorderWithHighMax.recordAttempt(delivery, WebhookAttemptResult(success = false, httpStatus = 500, responseBody = null, errorMessage = null))

        // attemptNumber = 3 -> backoff = min(10 * 2^2, 1000) = 40s
        assertThat(savedSlot.captured.nextAttemptAt).isAfter(before.plusSeconds(38))
        assertThat(savedSlot.captured.nextAttemptAt).isBefore(before.plusSeconds(42))
    }

    @Test
    fun `backoff is truly capped at maxDelaySeconds once the exponential value exceeds it`() {
        // Achado de revisão (reviewer, E8): o teste anterior ("backoff doubles...") nunca exercitava
        // o ramo em que 2^(attemptNumber-1) * baseDelaySeconds efetivamente ULTRAPASSA
        // maxDelaySeconds - com maxDelaySeconds=1000 e attemptNumber=3 (exponencial=40), minOf() nunca
        // truncava nada; um bug que trocasse minOf por maxOf (ou removesse o teto) não seria pego.
        // Aqui, attemptNumber=6 -> exponencial = 10 * 2^5 = 320, maxDelaySeconds=50 -> deve saturar em 50s.
        val delivery = pendingDelivery(attemptCount = 5) // about to become the 6th attempt
        every { deliveries.recordAttempt(any()) } answers { firstArg() }
        val savedSlot = slot<WebhookDelivery>()
        every { deliveries.save(capture(savedSlot)) } answers { savedSlot.captured }
        val propertiesWithLowCap = WebhookProperties().apply { maxAttempts = 10; baseDelaySeconds = 10; maxDelaySeconds = 50 }
        val recorderWithLowCap = WebhookDeliveryRecorder(deliveries, propertiesWithLowCap)
        val before = Instant.now()

        recorderWithLowCap.recordAttempt(delivery, WebhookAttemptResult(success = false, httpStatus = 500, responseBody = null, errorMessage = null))

        // attemptNumber = 6 -> uncapped would be 10 * 2^5 = 320s; capped -> exactly 50s.
        assertThat(savedSlot.captured.nextAttemptAt).isAfter(before.plusSeconds(48))
        assertThat(savedSlot.captured.nextAttemptAt).isBefore(before.plusSeconds(52))
    }

    @Test
    fun `a failed attempt that exhausts maxAttempts moves the delivery to FAILED, nextAttemptAt null`() {
        val delivery = pendingDelivery(attemptCount = 2) // maxAttempts = 3 -> this is the last try
        every { deliveries.recordAttempt(any()) } answers { firstArg() }
        val savedSlot = slot<WebhookDelivery>()
        every { deliveries.save(capture(savedSlot)) } answers { savedSlot.captured }

        recorder.recordAttempt(delivery, WebhookAttemptResult(success = false, httpStatus = null, responseBody = null, errorMessage = "timeout"))

        assertThat(savedSlot.captured.status).isEqualTo(WebhookDeliveryStatus.FAILED)
        assertThat(savedSlot.captured.attemptCount).isEqualTo(3)
        assertThat(savedSlot.captured.nextAttemptAt).isNull()
        val attemptSlot = slot<WebhookDeliveryAttempt>()
        verify(exactly = 1) { deliveries.recordAttempt(capture(attemptSlot)) }
        assertThat(attemptSlot.captured.success).isFalse()
        assertThat(attemptSlot.captured.errorMessage).isEqualTo("timeout")
    }
}
