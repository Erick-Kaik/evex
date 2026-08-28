package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.Network
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
)
