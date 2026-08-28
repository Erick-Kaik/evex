package br.com.bbchain.evex.indexer.domain.webhook

/** Status de uma [WebhookDelivery]. `FAILED` = esgotou `maxAttempts`, não é mais candidata a retry. */
enum class WebhookDeliveryStatus {
    PENDING,
    DELIVERED,
    FAILED,
}
