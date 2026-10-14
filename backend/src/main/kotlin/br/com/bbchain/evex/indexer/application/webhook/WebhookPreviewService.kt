package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentDiagnosis
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentFailureAggregate
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentInput
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentResolution
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentResolveOptions
import br.com.bbchain.evex.indexer.domain.enrichment.summarizeFailures
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.InvalidWebhookRequestException
import br.com.bbchain.evex.indexer.domain.webhook.TooManyEventsException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Códigos de aviso extensíveis (string) do preview. */
object WebhookPreviewWarnings {
    const val MODEL_STATE_NEWER_THAN_EVENT = "MODEL_STATE_NEWER_THAN_EVENT"
    const val PAYLOAD_RENDER_FAILED = "PAYLOAD_RENDER_FAILED"
    const val ENRICHMENT_FAILED = "ENRICHMENT_FAILED"
}

/** Bloco de enriquecimento efetivamente aplicado (resolvido uma vez por requisição). */
data class PreviewEnrichmentBlock(
    val mode: String,
    /** Bloco fixo (`BLOCK_NUMBER`/`BLOCK_AT_DATE`); `null` em `EVENT_BLOCK` (varia por evento) e `LATEST`. */
    val blockNumber: Long?,
    val resolvedFromDate: Instant?,
    val approximate: Boolean?,
    /** Origem da conversão data -> bloco (`INGESTED`/`RPC`/`COMBINED`) — só em `BLOCK_AT_DATE`. */
    val source: String?,
    val warnings: List<String>,
)

/** Estado dos Modelos de Entidade lido para o `{{model.*}}`: sempre o registro ATUAL (sem histórico). */
data class PreviewModelState(val stateAt: Instant?, val newerThanEvent: Boolean)

data class PreviewEnrichment(
    /** Bloco comum a todas as chamadas, ou `null` se variou/nenhuma chamada. */
    val blockUsed: String?,
    val calls: List<EnrichmentCallReport>,
    val unresolvedTokens: List<String>,
    val failuresSummary: List<EnrichmentFailureAggregate>,
    /** `null` quando o preview não pediu diagnóstico. Todo veredicto é INDÍCIO. */
    val diagnoses: List<EnrichmentDiagnosis>?,
)

data class PreviewItem(
    val eventId: UUID,
    val matches: Boolean,
    val notMatchingReason: String?,
    /** Payload final (Fase 1 + `{{enriched.*}}`); `null` se o evento não existe ou o render falhou. */
    val payload: String?,
    val modelState: PreviewModelState?,
    val enrichment: PreviewEnrichment?,
    val warnings: List<String>,
)

data class WebhookPreviewResult(
    val enrichmentBlock: PreviewEnrichmentBlock,
    val items: List<PreviewItem>,
    /** Falhas agregadas da REQUISIÇÃO inteira (todos os eventos). */
    val failuresSummary: List<EnrichmentFailureAggregate>,
    val warnings: List<String>,
)

/**
 * Pré-visualização (dry-run) do payload que um webhook enviaria para eventos já indexados (E33).
 *
 * **Efeito colateral zero**: só injeta portas de LEITURA ([WebhookConfigPort], [BlockchainEventPort]
 * `findAllByIds`, [EntityModelRecordPort] `findLatestUpdatedAt`) e componentes que só leem/renderizam
 * ([WebhookPayloadComposer], [WebhookEnrichmentResolver] — `eth_call` e ABI). Não conhece
 * `WebhookDeliveryPort`, `WebhookDispatchPort`, projeção de saldo/modelo nem redecode; nenhum POST é feito.
 * `@Transactional(readOnly = true)` na orquestração.
 *
 * Decisões documentadas:
 * - Evento que **não casa** com o webhook é renderizado mesmo assim, com `matches = false` e
 *   `notMatchingReason` — útil para testar template/filtros; o reenvio (fatia seguinte) não aceita isso.
 * - `{{model.*}}` usa o registro ATUAL do modelo (sem histórico de versões, DEC-111). [PreviewModelState]
 *   informa o `stateAt` (maior `updatedAt` lido) e `newerThanEvent` (`stateAt` > `blockTimestamp` do evento);
 *   nesse caso o item ganha o aviso `MODEL_STATE_NEWER_THAN_EVENT`.
 * - Um [br.com.bbchain.evex.indexer.application.webhook.EnrichmentExecutionContext] por REQUISIÇÃO: memoização e
 *   circuit-breaker (interativo) valem para todos os eventos do lote; a falha de uma chamada nunca derruba o
 *   lote (o payload mantém `null` no placeholder — DEC-057) e vem detalhada em `calls[]`.
 * - `BLOCK_AT_DATE`: a data é convertida UMA vez, com `FLOOR`, por [EnrichmentBlockResolver] (compartilhado com o reenvio);
 *   falha (ex.: data anterior ao gênesis) -> `BlockResolutionFailedException` (422).
 * - Conta 1 requisição na janela da instância de [ResendRateLimiter] (429 `RATE_LIMITED`); não grava nada.
 */
@Service
class WebhookPreviewService(
    private val webhookConfigs: WebhookConfigPort,
    private val events: BlockchainEventPort,
    private val composer: WebhookPayloadComposer,
    private val enrichmentResolver: WebhookEnrichmentResolver,
    private val entityModelRecords: EntityModelRecordPort,
    private val blockResolver: EnrichmentBlockResolver,
    private val properties: WebhookResendProperties,
    private val rateLimiter: ResendRateLimiter,
) {
    private val logger = LoggerFactory.getLogger(WebhookPreviewService::class.java)

    @Transactional(readOnly = true)
    fun preview(
        webhookId: UUID,
        eventIds: List<UUID>,
        enrichmentBlock: EnrichmentBlockSpec = EnrichmentBlockSpec.EventBlock,
        diagnose: Boolean = false,
    ): WebhookPreviewResult {
        val config = webhookConfigs.findById(webhookId) ?: throw WebhookConfigNotFoundException(webhookId)
        rateLimiter.checkPreview() // 429 RATE_LIMITED: teto de requisições por minuto na instância (E33/DEC-116)
        if (eventIds.isEmpty()) throw InvalidWebhookRequestException("eventIds não pode ser vazio")
        val ids = eventIds.distinct()
        if (ids.size > properties.previewMaxEvents) throw TooManyEventsException(properties.previewMaxEvents, ids.size)

        val (spec, blockInfo) = blockResolver.resolve(enrichmentBlock)
        val options = EnrichmentResolveOptions.interactive(diagnose)
        val context = EnrichmentExecutionContext(options) // UM por requisição (não thread-safe)
        val found = events.findAllByIds(ids).associateBy { it.id }

        val items = ids.map { id ->
            val event = found[id]
            if (event == null) notFoundItem(id) else previewEvent(config, event, spec, options, context, diagnose)
        }
        val summary = items.mapNotNull { it.enrichment?.calls }.flatten().summarizeFailures()
        return WebhookPreviewResult(blockInfo, items, summary, blockInfo.warnings)
    }

    private fun notFoundItem(id: UUID) = PreviewItem(
        eventId = id,
        matches = false,
        notMatchingReason = WebhookNotMatchingReasons.EVENT_NOT_FOUND,
        payload = null,
        modelState = null,
        enrichment = null,
        warnings = listOf(WebhookNotMatchingReasons.EVENT_NOT_FOUND),
    )

    private fun previewEvent(
        config: WebhookConfig,
        event: BlockchainEvent,
        spec: EnrichmentBlockSpec,
        options: EnrichmentResolveOptions,
        context: EnrichmentExecutionContext,
        diagnose: Boolean,
    ): PreviewItem {
        val reason = composer.notMatchingReason(config, event)
        val warnings = mutableListOf<String>()

        val composed = try {
            composer.compose(config, event)
        } catch (ex: Exception) {
            logger.debug("Preview: render do payload falhou para o evento {} ({})", event.id, ex.javaClass.simpleName)
            warnings += "${WebhookPreviewWarnings.PAYLOAD_RENDER_FAILED}: ${ex.message}"
            return PreviewItem(event.id, reason == null, reason, null, null, null, warnings)
        }

        val stateAt = composed.modelKeys.takeIf { it.isNotEmpty() }?.let { entityModelRecords.findLatestUpdatedAt(it) }
        val blockTimestamp = event.blockTimestamp
        val newer = stateAt != null && blockTimestamp != null && stateAt.isAfter(blockTimestamp)
        if (newer) warnings += WebhookPreviewWarnings.MODEL_STATE_NEWER_THAN_EVENT

        val resolution = enrichmentResolver.resolveDetailed(
            EnrichmentInput(event.blockNumber, event.decodedParameters, composed.payload, event.id),
            config.enrichmentProfileIds,
            spec,
            options,
            context,
        )
        val enrichment = resolution.toPreviewEnrichment(diagnose)
        if (enrichment.failuresSummary.isNotEmpty()) warnings += WebhookPreviewWarnings.ENRICHMENT_FAILED

        return PreviewItem(
            eventId = event.id,
            matches = reason == null,
            notMatchingReason = reason,
            payload = resolution.payload,
            modelState = PreviewModelState(stateAt, newer),
            enrichment = enrichment,
            warnings = warnings,
        )
    }

    private fun EnrichmentResolution.toPreviewEnrichment(diagnose: Boolean): PreviewEnrichment {
        val calls = outcomes.map { EnrichmentCallReport.from(it) }
        return PreviewEnrichment(
            blockUsed = calls.map { it.blockUsed }.distinct().singleOrNull(),
            calls = calls,
            unresolvedTokens = unresolvedTokens,
            failuresSummary = calls.summarizeFailures(),
            diagnoses = if (diagnose) diagnoses else null,
        )
    }
}
