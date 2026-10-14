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

    /**
     * Entregas `PENDING` com `nextAttemptAt` nulo ou `<= now()`, até [limit] — consultado por
     * `WebhookDispatchJob`. Ordem: disparos normais (`origin = MATCH`) ANTES de reenvios, depois
     * `createdAt` — um reenvio grande/lento nunca atrasa as entregas em tempo real (E33).
     */
    fun findDue(limit: Int): List<WebhookDelivery>

    /**
     * Enfileira um REENVIO (`origin != MATCH`) de forma atômica e sem corrida: o banco garante no máximo uma entrega
     * `PENDING` de reenvio por (webhook, evento) (índice único parcial). Devolve a entrega gravada ou `null` quando
     * já existia uma pendente (outra requisição concorrente) — nada é gravado nesse caso e a transação chamadora
     * continua utilizável (sem exceção de framework nem rollback-only).
     */
    fun enqueueResendIfAbsent(delivery: WebhookDelivery): WebhookDelivery?

    /** Atualiza status/`attemptCount`/`nextAttemptAt` de uma entrega já existente. */
    fun save(delivery: WebhookDelivery): WebhookDelivery

    fun recordAttempt(attempt: WebhookDeliveryAttempt): WebhookDeliveryAttempt

    fun findAttempts(deliveryId: UUID): List<WebhookDeliveryAttempt>

    /** Já existe entrega de reenvio (`origin != MATCH`) `PENDING` para este par webhook/evento? Guarda contra duplo clique. */
    fun existsPendingResend(webhookConfigId: UUID, blockchainEventId: UUID): Boolean

    /** Total de entregas de reenvio (`origin != MATCH`) `PENDING` — teto de fila de reenvios. */
    fun countPendingResend(): Long

    fun search(webhookConfigId: UUID?, status: WebhookDeliveryStatus?, page: Int, size: Int): PageResult<WebhookDelivery>
}
