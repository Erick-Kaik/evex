package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyResolver
import br.com.bbchain.evex.indexer.domain.model.CorrelationResult
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordKey
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.matches
import org.springframework.stereotype.Component

/**
 * Resultado de [WebhookPayloadComposer.compose]: o [payload] da Fase 1 (ainda com `{{enriched.*}}`
 * intactos) e as [modelKeys] dos registros de Modelo de Entidade consultados (chave resolvida,
 * existindo ou não o registro) — quem precisa do "estado do modelo" (preview/reenvio) pergunta o
 * `updatedAt` por elas via [EntityModelRecordPort.findLatestUpdatedAt].
 */
data class ComposedPayload(
    val payload: String,
    val modelKeys: List<EntityModelRecordKey>,
)

/** Códigos extensíveis (string, nunca enum) do porquê um evento não casa com um webhook. */
object WebhookNotMatchingReasons {
    const val EVENT_NOT_DECODED = "EVENT_NOT_DECODED"
    const val CONTRACT_MISMATCH = "CONTRACT_MISMATCH"
    const val EVENT_NAME_MISMATCH = "EVENT_NAME_MISMATCH"
    const val NETWORK_MISMATCH = "NETWORK_MISMATCH"
    const val FILTER_NOT_MET = "FILTER_NOT_MET"
    const val FILTER_EVALUATION_ERROR = "FILTER_EVALUATION_ERROR"
    const val EVENT_NOT_FOUND = "EVENT_NOT_FOUND"
}

/**
 * Fase 1 do webhook (casamento do evento com a config + render do payload + `{{model.*}}`), extraída de
 * `WebhookMatchingService.matchSafely` (E33) para ser **a mesma função** no disparo normal, na
 * pré-visualização e no reenvio — o payload não diverge entre os três.
 *
 * **Leitura + render apenas: não escreve nada** (só consulta [EntityModelPort] e [EntityModelRecordPort]
 * em modo leitura). Não captura exceções: filtro/template malformado propaga para quem chama (o
 * `matchSafely` registra o WARN e segue; o preview reporta no item).
 *
 * O `{{model.*}}` usa o registro **atual** do Modelo de Entidade — o registro é acumulado por chave e não
 * guarda histórico de versões (DEC-111), então um preview/reenvio posterior ao evento enxerga o estado
 * de agora, não o do momento do evento.
 */
@Component
class WebhookPayloadComposer(
    private val payloadRenderer: WebhookPayloadRenderer,
    private val entityModels: EntityModelPort,
    private val entityModelRecords: EntityModelRecordPort,
    private val correlationKeyResolver: CorrelationKeyResolver = CorrelationKeyResolver(),
) {
    /** E19/DEC-082 — prefixo reservado sempre passado pro renderer: `{{enriched.*}}` só resolve na Fase 2. */
    private val reservedPrefixes = setOf("enriched.")

    /** Só os `filters` da config contra os parâmetros decodificados (o que o disparo normal avalia — contrato/evento já vêm de `findActive`). */
    fun filtersMatch(config: WebhookConfig, event: BlockchainEvent): Boolean {
        val parameters = event.decodedParameters.orEmpty()
        return config.filters.all { it.matches(parameters) }
    }

    /**
     * Por que [event] NÃO casa com [config] (código de [WebhookNotMatchingReasons]) ou `null` se casa.
     * Mais completo que [filtersMatch]: confere decodificação, contrato, evento, rede e filtros — usado
     * pelo preview, que aceita eventos arbitrários.
     */
    fun notMatchingReason(config: WebhookConfig, event: BlockchainEvent): String? = when {
        !event.decoded || event.eventName == null -> WebhookNotMatchingReasons.EVENT_NOT_DECODED
        !event.contractAddress.equals(config.contractAddress, ignoreCase = true) -> WebhookNotMatchingReasons.CONTRACT_MISMATCH
        event.eventName != config.eventName -> WebhookNotMatchingReasons.EVENT_NAME_MISMATCH
        event.network != config.network -> WebhookNotMatchingReasons.NETWORK_MISMATCH
        else -> try {
            if (filtersMatch(config, event)) null else WebhookNotMatchingReasons.FILTER_NOT_MET
        } catch (ex: Exception) {
            WebhookNotMatchingReasons.FILTER_EVALUATION_ERROR
        }
    }

    /** Renderiza o payload da Fase 1 (template ou envelope padrão) com `{{model.*}}` resolvido em memória. */
    fun compose(config: WebhookConfig, event: BlockchainEvent): ComposedPayload {
        val modelKeys = mutableListOf<EntityModelRecordKey>()
        val payload = config.payloadTemplate
            ?.let { template ->
                // {{model.*}} é resolvido aqui, em memória, junto com {{decodedParameters.*}} —
                // não precisa de rede (diferente de {{enriched.*}}, que é resolvido só na Fase 2).
                val modelPlaceholders = resolveModelPlaceholders(config, event, modelKeys)
                payloadRenderer.renderTemplate(template, event, reservedPrefixes = reservedPrefixes, extraPlaceholders = modelPlaceholders)
            }
            ?: payloadRenderer.renderDefault(event)
        return ComposedPayload(payload, modelKeys)
    }

    /**
     * Resolve `{{model.<campo>}}` a partir dos Modelos de Entidade referenciados por [config]
     * (E19/DEC-082) — 100% em memória/banco, cabe na Fase 1. Pra cada modelo, acha o
     * [br.com.bbchain.evex.indexer.domain.model.ModelContributor] cujo `(contractAddress, eventName)`
     * bate com o [event]; se achar, usa o [CorrelationKeyResolver] (mesma chave da projeção, DEC-111)
     * pra obter o valor de correlação e busca o registro acumulado. Evento que não é contribuinte
     * conhecido do modelo (ou registro ainda inexistente) simplesmente não entra no mapa.
     */
    private fun resolveModelPlaceholders(
        config: WebhookConfig,
        event: BlockchainEvent,
        keysOut: MutableList<EntityModelRecordKey>,
    ): Map<String, Any?> {
        if (config.entityModelIds.isEmpty()) return emptyMap()
        val eventName = event.eventName ?: return emptyMap()
        val placeholders = mutableMapOf<String, Any?>()
        for (model in entityModels.findAllByIds(config.entityModelIds)) {
            val contributor = model.contributors.find {
                it.contractAddress.equals(event.contractAddress, ignoreCase = true) && it.eventName == eventName
            } ?: continue
            val correlationValue = (correlationKeyResolver.resolve(contributor, event) as? CorrelationResult.Resolved)?.key ?: continue
            keysOut += EntityModelRecordKey(model.id, correlationValue, event.network)
            val record = entityModelRecords.findRecord(model.id, correlationValue, event.network) ?: continue
            record.data.forEach { (field, value) -> placeholders["model.$field"] = value }
        }
        return placeholders
    }
}
