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
 *
 * [blockNumber]/[decodedParameters] (E19/DEC-082) — mesmo raciocínio de desnormalização de [network]
 * acima ("evita depender do `blockchain_event` continuar existindo/acessível depois"): snapshot do
 * evento que originou esta entrega, tirado no `enqueue` (Fase 1,
 * [br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService]). [decodedParameters] é o
 * mesmo tipo de
 * [br.com.bbchain.evex.indexer.domain.event.BlockchainEvent.decodedParameters] (`Map<String, Any?>?`)
 * — sem transformação. Consumido na Fase 2 (dispatch,
 * [br.com.bbchain.evex.indexer.application.webhook.WebhookEnrichmentResolver]) pra montar os `args` das
 * `EnrichmentCall`s referenciadas (`{{decodedParameters.x}}`) e pra resolver `eth_call` no bloco exato
 * do evento — nunca o bloco atual/`latest`. Nulo/default só em entregas persistidas antes da E19 (ou em
 * qualquer teste que não os define); toda entrega criada pela E19 em diante os popula sempre, com ou
 * sem perfil/modelo referenciado (custo de guardar 2 campos a mais é desprezível e evita um `NULL`
 * condicional dependendo de feature habilitada).
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
    val blockNumber: Long? = null,
    val decodedParameters: Map<String, Any?>? = null,
)
