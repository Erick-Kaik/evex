package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.adapters.outbound.webhook.WebhookPayloadRenderer
import br.com.bbchain.evex.indexer.application.common.WarnLogDeduper
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyResolver
import br.com.bbchain.evex.indexer.domain.model.CorrelationResult
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.matches
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Casa eventos recém-decodificados contra [WebhookConfig]s ativas e enfileira as entregas que
 * baterem (doc §4.6) — chamada por [br.com.bbchain.evex.indexer.adapters.outbound.blockchain.
 * IngestionBatchProcessor] (E4, eventos novos) e [br.com.bbchain.evex.indexer.application.redecode.
 * RedecodeBatchProcessor] (E5, eventos que acabaram de virar `decoded=true`) — mesmo ponto de
 * integração de [br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService] (E7).
 *
 * **Sem `@Transactional` próprio** — roda dentro da transação já aberta por quem chama: só grava a
 * `WebhookDelivery` (`status=PENDING`) no banco, nenhuma chamada HTTP acontece aqui (isso é
 * `WebhookDispatchJob`, `@Scheduled`, fora da transação de ingestão — regra não-negociável do doc,
 * "não pode ser síncrona bloqueando a ingestão").
 *
 * Reaproveita a lição da DEC-035 (E7) desde o início desta etapa: `try/catch` por combinação
 * evento×config (não por evento inteiro nem por lote inteiro) — 1 config mal configurada (filtro
 * apontando pra parâmetro incompatível, template malformado) nunca deve derrubar a transação de
 * ingestão/redecode.
 */
@Service
class WebhookMatchingService(
    private val webhookConfigs: WebhookConfigPort,
    private val deliveries: WebhookDeliveryPort,
    private val payloadRenderer: WebhookPayloadRenderer,
    private val entityModels: EntityModelPort,
    private val entityModelRecords: EntityModelRecordPort,
    indexerProperties: IndexerProperties,
    private val correlationKeyResolver: CorrelationKeyResolver = CorrelationKeyResolver(),
) {
    private val logger = LoggerFactory.getLogger(WebhookMatchingService::class.java)

    /** E19/DEC-082 — prefixo reservado sempre passado pro renderer: `{{enriched.*}}` só resolve na Fase 2. */
    private val reservedPrefixes = setOf("enriched.")

    /** E11 (item 4, resíduo de DEC-035) — rate-limita o `WARN` abaixo por `webhookConfig.id`. */
    private val warnLogDeduper = WarnLogDeduper(indexerProperties.warnLogDedupeInterval)

    fun matchAndEnqueue(events: List<BlockchainEvent>) {
        for (event in events) {
            val eventName = event.eventName
            if (!event.decoded || eventName == null) continue
            val activeConfigs = webhookConfigs.findActive(event.contractAddress, eventName)
            for (config in activeConfigs) {
                matchSafely(config, event)
            }
        }
    }

    private fun matchSafely(config: WebhookConfig, event: BlockchainEvent) {
        try {
            val parameters = event.decodedParameters.orEmpty()
            val matched = config.filters.all { it.matches(parameters) }
            if (!matched) return

            val payload = config.payloadTemplate
                ?.let { template ->
                    // {{model.*}} é resolvido aqui, em memória, junto com {{decodedParameters.*}} —
                    // não precisa de rede (diferente de {{enriched.*}}, que é resolvido só na Fase 2).
                    val modelPlaceholders = resolveModelPlaceholders(config, event)
                    payloadRenderer.renderTemplate(template, event, reservedPrefixes = reservedPrefixes, extraPlaceholders = modelPlaceholders)
                }
                ?: payloadRenderer.renderDefault(event)

            deliveries.enqueue(
                WebhookDelivery(
                    id = UUID.randomUUID(),
                    webhookConfigId = config.id,
                    blockchainEventId = event.id,
                    network = config.network,
                    payload = payload,
                    status = WebhookDeliveryStatus.PENDING,
                    attemptCount = 0,
                    nextAttemptAt = null,
                    createdAt = Instant.now(),
                    blockNumber = event.blockNumber,
                    decodedParameters = event.decodedParameters,
                ),
            )
        } catch (ex: Exception) {
            // E11 (item 4, resíduo de DEC-035): rate-limitado por webhookConfig.id — sem isso, uma
            // config persistentemente mal configurada (filtro/template quebrado nunca corrigido) gera
            // 1 linha de WARN a cada evento que bate nela.
            if (warnLogDeduper.shouldLog(config.id)) {
                logger.warn(
                    "Falha ao casar webhookConfig={} pro evento (contractAddress={}, eventName={}, txHash={}, logIndex={}): {}",
                    config.id,
                    event.contractAddress,
                    event.eventName,
                    event.txHash,
                    event.logIndex,
                    ex.message,
                )
            }
        }
    }

    /**
     * Resolve `{{model.<campo>}}` a partir dos Modelos de Entidade referenciados por [config]
     * (E19/DEC-082) — 100% em memória/banco, cabe na Fase 1 (não precisa de rede como
     * `{{enriched.*}}`). Pra cada modelo, acha o [br.com.bbchain.evex.indexer.domain.model.
     * ModelContributor] cujo `(contractAddress, eventName)` bate com o [event] **atual**; se achar, usa
     * o [br.com.bbchain.evex.indexer.domain.model.CorrelationKeyResolver] (mesma chave da projeção) pra obter o valor de correlação e busca o
     * registro acumulado ([EntityModelRecordPort.findRecord]). Se o evento não for um contribuinte
     * conhecido do modelo (ou o registro ainda não existir), os campos daquele modelo simplesmente não
     * entram no mapa — mesma resiliência do resto do projeto (erro de configuração, não erro fatal
     * aqui; deveria ter sido pego no cadastro do webhook, `WebhookConfigService`).
     */
    private fun resolveModelPlaceholders(config: WebhookConfig, event: BlockchainEvent): Map<String, Any?> {
        if (config.entityModelIds.isEmpty()) return emptyMap()
        val eventName = event.eventName ?: return emptyMap()
        val placeholders = mutableMapOf<String, Any?>()
        for (model in entityModels.findAllByIds(config.entityModelIds)) {
            val contributor = model.contributors.find {
                it.contractAddress.equals(event.contractAddress, ignoreCase = true) && it.eventName == eventName
            } ?: continue
            // Mesmo resolver da projeção (DEC-111): evento -> chave -> registro compartilhado.
            val correlationValue = (correlationKeyResolver.resolve(contributor, event) as? CorrelationResult.Resolved)?.key ?: continue
            val record = entityModelRecords.findRecord(model.id, correlationValue, event.network) ?: continue
            record.data.forEach { (field, value) -> placeholders["model.$field"] = value }
        }
        return placeholders
    }
}
