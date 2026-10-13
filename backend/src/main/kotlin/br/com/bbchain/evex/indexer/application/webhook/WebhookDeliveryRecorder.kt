package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private const val MAX_RESPONSE_BODY_LENGTH = 2_000

/**
 * Grava o resultado de 1 tentativa de disparo e atualiza o status da [WebhookDelivery]. Bean
 * **separado** de [WebhookDispatchJob] — mesma razão de self-invocation já documentada pro
 * `@Transactional` (`IngestionBatchProcessor`/E4, `RedecodeBatchProcessor`/E5): `@Transactional` não
 * funciona quando o método é chamado a partir da mesma classe (não passa pelo proxy do Spring).
 *
 * Backoff exponencial com teto: `min(baseDelaySeconds * 2^(attemptCount-1), maxDelaySeconds)`.
 */
@Component
class WebhookDeliveryRecorder(
    private val deliveries: WebhookDeliveryPort,
    private val properties: WebhookProperties,
) {
    /**
     * [enrichmentResults]/[enrichmentBlockUsed]/[sentPayload] (E33) são opcionais — `null` = nada
     * registrado, comportamento idêntico ao anterior (o `WebhookDispatchJob` os liga na fatia seguinte).
     */
    @Transactional
    fun recordAttempt(
        delivery: WebhookDelivery,
        result: WebhookAttemptResult,
        enrichmentResults: List<EnrichmentCallReport>? = null,
        enrichmentBlockUsed: String? = null,
        sentPayload: String? = null,
    ): WebhookDelivery {
        val attemptNumber = delivery.attemptCount + 1
        deliveries.recordAttempt(
            WebhookDeliveryAttempt(
                id = UUID.randomUUID(),
                deliveryId = delivery.id,
                network = delivery.network,
                attemptNumber = attemptNumber,
                attemptedAt = Instant.now(),
                success = result.success,
                httpStatus = result.httpStatus,
                responseBody = result.responseBody?.take(MAX_RESPONSE_BODY_LENGTH),
                errorMessage = result.errorMessage,
                enrichmentResults = enrichmentResults,
                enrichmentBlockUsed = enrichmentBlockUsed,
                sentPayload = sentPayload,
            ),
        )

        val updated = when {
            result.success -> delivery.copy(status = WebhookDeliveryStatus.DELIVERED, attemptCount = attemptNumber, nextAttemptAt = null)
            attemptNumber >= properties.maxAttempts -> delivery.copy(status = WebhookDeliveryStatus.FAILED, attemptCount = attemptNumber, nextAttemptAt = null)
            else -> delivery.copy(
                status = WebhookDeliveryStatus.PENDING,
                attemptCount = attemptNumber,
                nextAttemptAt = Instant.now().plusSeconds(backoffSeconds(attemptNumber)),
            )
        }
        return deliveries.save(updated)
    }

    private fun backoffSeconds(attemptNumber: Int): Long {
        val exponential = properties.baseDelaySeconds * (1L shl (attemptNumber - 1))
        return minOf(exponential, properties.maxDelaySeconds)
    }
}
