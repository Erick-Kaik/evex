package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.application.common.WarnLogDeduper
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
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
    private val composer: WebhookPayloadComposer,
    indexerProperties: IndexerProperties,
) {
    private val logger = LoggerFactory.getLogger(WebhookMatchingService::class.java)

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
            if (!composer.filtersMatch(config, event)) return

            // Fase 1 (casamento/render/{{model.*}}) compartilhada com preview e reenvio (E33).
            val payload = composer.compose(config, event).payload

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
}
