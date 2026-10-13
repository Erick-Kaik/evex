package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.InvalidWebhookRequestException
import br.com.bbchain.evex.indexer.domain.webhook.OutboundUrlPolicy
import br.com.bbchain.evex.indexer.domain.webhook.RateLimitExceededException
import br.com.bbchain.evex.indexer.domain.webhook.TooManyEventsException
import br.com.bbchain.evex.indexer.domain.webhook.UrlCheck
import br.com.bbchain.evex.indexer.domain.webhook.UrlNotAllowedException
import br.com.bbchain.evex.indexer.domain.webhook.UrlOverrideDisabledException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryOrigin
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookInactiveException
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Seleção por intervalo (bloco e/ou data) — enfileira os eventos que CASAM, até o teto por requisição. */
data class ResendRange(
    val fromBlock: Long? = null,
    val toBlock: Long? = null,
    val fromDate: Instant? = null,
    val toDate: Instant? = null,
)

data class ResendAccepted(val eventId: UUID, val deliveryId: UUID)

/** [reason] é um código extensível ([WebhookResendSkipReasons]). */
data class ResendSkipped(val eventId: UUID, val reason: String)

/** Códigos de aviso extensíveis do reenvio. */
object WebhookResendWarnings {
    /** O `range` casou mais eventos que o teto por requisição: só os mais recentes até o teto foram enfileirados. */
    const val RANGE_TRUNCATED = "RANGE_TRUNCATED"

    /** A varredura do `range` atingiu `max-scan` sem esgotar os dados. */
    const val SCAN_TRUNCATED = "SCAN_TRUNCATED"
}

/** Motivos (strings extensíveis) de um evento não ter sido enfileirado no reenvio. */
object WebhookResendSkipReasons {
    const val NOT_MATCHING = "NOT_MATCHING"
    const val ALREADY_PENDING = "ALREADY_PENDING"
    const val EVENT_NOT_DECODED = WebhookNotMatchingReasons.EVENT_NOT_DECODED
    const val EVENT_NOT_FOUND = WebhookNotMatchingReasons.EVENT_NOT_FOUND
    const val PAYLOAD_RENDER_FAILED = WebhookPreviewWarnings.PAYLOAD_RENDER_FAILED
}

data class WebhookResendOutcome(
    val accepted: List<ResendAccepted>,
    val skipped: List<ResendSkipped>,
    val modelState: PreviewModelState,
    val enrichmentBlock: PreviewEnrichmentBlock,
    val warnings: List<String>,
)

/**
 * Reenvio manual de eventos já indexados para um webhook (E33 / DEC-116): cria uma nova [WebhookDelivery]
 * `PENDING` (`origin = RESEND`) por evento que casa, pelo MESMO `WebhookDeliveryPort.enqueue` do disparo normal;
 * o `WebhookDispatchJob` a processa (Fase 2 do enriquecimento, POST, retry/backoff, log de tentativas) sem
 * código novo de fila.
 *
 * **Efeito colateral estritamente limitado**: o único write é `webhook_deliveries`. Não conhece
 * `BlockchainEventPort.updateAll/saveAll`, projeção de saldo, projeção de modelo nem redecode — `decoded` e
 * `decoded_parameters` nunca mudam.
 *
 * **Estado do modelo**: o `{{model.*}}` do payload usa o registro do Modelo de Entidade ATUAL no momento do
 * reenvio (o registro é acumulado por chave, sem histórico — DEC-111), que pode ter avançado depois do evento.
 * `modelStateAt` (maior `updatedAt` lido) é gravado na entrega e devolvido em `modelState`, com o aviso
 * `MODEL_STATE_NEWER_THAN_EVENT` quando o registro é posterior ao evento.
 *
 * **Modo de bloco** (mesma regra do preview, [EnrichmentBlockResolver]): `BLOCK_AT_DATE` é resolvido por `FLOOR` e
 * CONGELADO como número no enqueue; `BLOCK_NUMBER` fica congelado; `EVENT_BLOCK` (bloco de cada evento) e `LATEST`
 * (re-resolvido a cada tentativa, D6) guardam só o modo.
 *
 * **Regras de entrada** (ordem): webhook inexistente 404 -> limite de taxa 429 -> `eventIds` XOR `range` (400) ->
 * teto `max-events-per-request` (400 `TOO_MANY_EVENTS`, nada é enfileirado) -> `overrideUrl` (desligada 400,
 * bloqueada pela política 400) -> webhook inativo 409 (exceto com `overrideUrl` válida) -> modo de bloco ->
 * teto de fila `max-pending-resends` (429 `RESEND_QUEUE_FULL`, tudo-ou-nada). `@Transactional`: qualquer erro
 * desfaz o que já tinha sido enfileirado.
 */
@Service
class WebhookResendService(
    private val webhookConfigs: WebhookConfigPort,
    private val events: BlockchainEventPort,
    private val deliveries: WebhookDeliveryPort,
    private val composer: WebhookPayloadComposer,
    private val matchingEvents: WebhookMatchingEventsService,
    private val entityModelRecords: EntityModelRecordPort,
    private val blockResolver: EnrichmentBlockResolver,
    private val urlPolicy: OutboundUrlPolicy,
    private val rateLimiter: ResendRateLimiter,
    private val properties: WebhookResendProperties,
) {
    @Transactional
    fun resend(
        webhookId: UUID,
        eventIds: List<UUID>?,
        range: ResendRange?,
        enrichmentBlock: EnrichmentBlockSpec = EnrichmentBlockSpec.EventBlock,
        overrideUrl: String? = null,
    ): WebhookResendOutcome {
        val config = webhookConfigs.findById(webhookId) ?: throw WebhookConfigNotFoundException(webhookId)
        rateLimiter.checkResend(webhookId)

        if ((eventIds == null) == (range == null)) {
            throw InvalidWebhookRequestException("Informe eventIds OU range (exatamente um dos dois)")
        }
        val limit = properties.maxEventsPerRequest
        val ids = eventIds?.distinct()
        if (ids != null) {
            if (ids.isEmpty()) throw InvalidWebhookRequestException("eventIds não pode ser vazio")
            if (ids.size > limit) throw TooManyEventsException(limit, ids.size)
        }

        // Presente-mas-em-branco é erro de validação (antes de qualquer política); só `null` significa "sem override".
        if (overrideUrl != null && overrideUrl.isBlank()) {
            throw InvalidWebhookRequestException("overrideUrl não pode ser vazio; omita o campo para usar a URL do webhook")
        }
        val targetUrl = overrideUrl?.let { validatedOverride(it) }
        if (!config.active && targetUrl == null) throw WebhookInactiveException(webhookId)

        val (spec, blockInfo) = blockResolver.resolve(enrichmentBlock)

        val warnings = mutableListOf<String>()
        val candidates: List<BlockchainEvent>
        val skipped = mutableListOf<ResendSkipped>()
        if (ids != null) {
            val found = events.findAllByIds(ids).associateBy { it.id }
            candidates = ids.mapNotNull { id ->
                found[id] ?: run {
                    skipped += ResendSkipped(id, WebhookResendSkipReasons.EVENT_NOT_FOUND)
                    null
                }
            }
        } else {
            val page = matchingEvents.listMatchingEvents(
                webhookId, range!!.fromBlock, range.toBlock, range.fromDate, range.toDate, cursor = null, size = limit,
            )
            candidates = page.events
            if (page.scanTruncated) warnings += WebhookResendWarnings.SCAN_TRUNCATED
            if (page.nextCursor != null && !page.scanTruncated) warnings += WebhookResendWarnings.RANGE_TRUNCATED
            warnings += page.warnings
        }

        // Fase de seleção: casa, guarda anti-duplo-clique e compõe o payload — ainda SEM escrever nada.
        data class Planned(val event: BlockchainEvent, val payload: String, val stateAt: Instant?)
        val planned = mutableListOf<Planned>()
        for (event in candidates) {
            val reason = composer.notMatchingReason(config, event)
            if (reason != null) {
                skipped += ResendSkipped(
                    event.id,
                    if (reason == WebhookNotMatchingReasons.EVENT_NOT_DECODED) WebhookResendSkipReasons.EVENT_NOT_DECODED
                    else WebhookResendSkipReasons.NOT_MATCHING,
                )
                continue
            }
            if (deliveries.existsPendingResend(webhookId, event.id)) {
                skipped += ResendSkipped(event.id, WebhookResendSkipReasons.ALREADY_PENDING)
                continue
            }
            val composed = try {
                composer.compose(config, event)
            } catch (ex: Exception) {
                skipped += ResendSkipped(event.id, WebhookResendSkipReasons.PAYLOAD_RENDER_FAILED)
                continue
            }
            val stateAt = composed.modelKeys.takeIf { it.isNotEmpty() }?.let { entityModelRecords.findLatestUpdatedAt(it) }
            planned += Planned(event, composed.payload, stateAt)
        }

        // Teto de fila, tudo-ou-nada: checado ANTES de qualquer enqueue.
        if (planned.isNotEmpty()) {
            val pending = deliveries.countPendingResend()
            if (pending + planned.size > properties.maxPendingResends) {
                throw RateLimitExceededException(
                    RateLimitExceededException.RESEND_QUEUE_FULL,
                    properties.queueFullRetryAfterSeconds,
                    "Fila de reenvios cheia (limite ${properties.maxPendingResends}); tente novamente em ${properties.queueFullRetryAfterSeconds} segundo(s)",
                )
            }
        }

        val now = Instant.now()
        val accepted = mutableListOf<ResendAccepted>()
        for (p in planned) {
            // O índice único parcial do banco é a garantia final contra reenvios concorrentes do mesmo par
            // (webhook, evento): quem perde a corrida recebe `null` e vira skip, sem abortar o lote.
            val delivery = deliveries.enqueueResendIfAbsent(
                WebhookDelivery(
                    id = UUID.randomUUID(),
                    webhookConfigId = config.id,
                    blockchainEventId = p.event.id,
                    network = config.network,
                    payload = p.payload,
                    status = WebhookDeliveryStatus.PENDING,
                    attemptCount = 0,
                    nextAttemptAt = null,
                    createdAt = now,
                    blockNumber = p.event.blockNumber,
                    decodedParameters = p.event.decodedParameters,
                    origin = WebhookDeliveryOrigin.RESEND,
                    enrichmentBlockMode = spec.mode,
                    enrichmentBlockNumber = if (spec.mode == EnrichmentBlockSpec.BLOCK_NUMBER || spec.mode == EnrichmentBlockSpec.BLOCK_AT_DATE) spec.blockNumber else null,
                    targetUrlOverride = targetUrl,
                    modelStateAt = p.stateAt,
                ),
            )
            if (delivery == null) {
                skipped += ResendSkipped(p.event.id, WebhookResendSkipReasons.ALREADY_PENDING)
            } else {
                accepted += ResendAccepted(p.event.id, delivery.id)
            }
        }

        val stateAt = planned.mapNotNull { it.stateAt }.maxOrNull()
        val newer = planned.any { p ->
            val ts = p.event.blockTimestamp
            p.stateAt != null && ts != null && p.stateAt.isAfter(ts)
        }
        if (newer) warnings += WebhookPreviewWarnings.MODEL_STATE_NEWER_THAN_EVENT
        return WebhookResendOutcome(accepted, skipped, PreviewModelState(stateAt, newer), blockInfo, warnings)
    }

    /** Capacidade ligada + política de destino; devolve a URL COMPLETA validada (só vai para o banco). */
    private fun validatedOverride(url: String): String {
        if (!properties.allowUrlOverride) throw UrlOverrideDisabledException()
        if (urlPolicy.check(url) !is UrlCheck.Allowed) throw UrlNotAllowedException()
        return url.trim()
    }
}
