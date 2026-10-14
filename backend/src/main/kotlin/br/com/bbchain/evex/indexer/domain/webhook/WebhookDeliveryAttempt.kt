package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import java.time.Instant
import java.util.UUID

/**
 * Log de 1 tentativa de entrega de um [WebhookDelivery] — sempre gravado, sucesso ou falha
 * ([WebhookDeliveryRecorder][br.com.bbchain.evex.indexer.application.webhook.WebhookDeliveryRecorder]).
 * [httpStatus]/[responseBody] presentes quando o `POST` chegou a acontecer (mesmo que 4xx/5xx);
 * [errorMessage] presente quando a chamada falhou antes de ter resposta HTTP (timeout, conexão
 * recusada, DNS, etc.) — [httpStatus] fica `null` nesse caso.
 *
 * [network] denormalizado de [WebhookDelivery.network] (E8.1, mesma razão do campo em [WebhookDelivery]).
 */
data class WebhookDeliveryAttempt(
    val id: UUID,
    val deliveryId: UUID,
    val network: Network,
    val attemptNumber: Int,
    val attemptedAt: Instant,
    val success: Boolean,
    val httpStatus: Int?,
    val responseBody: String?,
    val errorMessage: String?,
    /** Resultado por chamada de enriquecimento NESTA tentativa (a Fase 2 roda a cada tentativa); `null` = não registrado (E33). */
    val enrichmentResults: List<EnrichmentCallReport>? = null,
    /** Bloco usado nesta tentativa: número em decimal ou `latest`. */
    val enrichmentBlockUsed: String? = null,
    /** Payload final efetivamente enviado (após enriquecimento). */
    val sentPayload: String? = null,
)
