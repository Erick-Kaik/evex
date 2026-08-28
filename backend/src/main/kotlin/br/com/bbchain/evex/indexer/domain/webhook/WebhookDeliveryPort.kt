package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.util.UUID

/**
 * Porta de saída para a fila/log de entregas de webhook. Implementação JPA/Postgres em
 * `adapters/outbound/persistence/JpaWebhookDeliveryAdapter`.
 */
interface WebhookDeliveryPort {
    /** Grava uma nova entrega `PENDING` — chamado por `WebhookMatchingService`, dentro da transação de ingestão/redecode. */
    fun enqueue(delivery: WebhookDelivery): WebhookDelivery

    /** Entregas `PENDING` com `nextAttemptAt` nulo ou `<= now()`, até [limit] — consultado por `WebhookDispatchJob`. */
    fun findDue(limit: Int): List<WebhookDelivery>

    /** Atualiza status/`attemptCount`/`nextAttemptAt` de uma entrega já existente. */
    fun save(delivery: WebhookDelivery): WebhookDelivery

    fun recordAttempt(attempt: WebhookDeliveryAttempt): WebhookDeliveryAttempt

    fun findAttempts(deliveryId: UUID): List<WebhookDeliveryAttempt>

    fun search(webhookConfigId: UUID?, status: WebhookDeliveryStatus?, page: Int, size: Int): PageResult<WebhookDelivery>
}
