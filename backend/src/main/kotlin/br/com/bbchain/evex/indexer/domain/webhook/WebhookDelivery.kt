package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Uma entrega enfileirada de um [WebhookConfig] pra um evento decodificado — criada por
 * `WebhookMatchingService.matchAndEnqueue()` (mesma transação da ingestão/redecode, só grava no
 * banco, nenhuma chamada HTTP) e processada assincronamente por `WebhookDispatchJob` (`@Scheduled`,
 * fora da transação de ingestão).
 *
 * [payload] já vem renderizado no momento do match (não recalculado no dispatch) — o
 * `payloadTemplate`/envelope padrão usado é o vigente no cadastro do [webhookConfigId] naquele
 * instante, mesmo que a config seja editada depois.
 *
 * [network] denormalizado de [WebhookConfig.network] no momento do `enqueue` (E8.1 — antes só
 * acessível indiretamente via join com `webhookConfigId`, ressalva não-bloqueante da revisão da E8):
 * fecha a regra "campo `network` em toda entidade" sem exceção.
 */
data class WebhookDelivery(
    val id: UUID,
    val webhookConfigId: UUID,
    val blockchainEventId: UUID,
    val network: Network,
    val payload: String,
    val status: WebhookDeliveryStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val createdAt: Instant,
)
