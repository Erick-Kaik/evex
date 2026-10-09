package br.com.bbchain.evex.indexer.application.model

import br.com.bbchain.evex.indexer.application.common.WarnLogDeduper
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyResolver
import br.com.bbchain.evex.indexer.domain.model.CorrelationResult
import br.com.bbchain.evex.indexer.domain.model.FieldValueResult
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Acumula campos de eventos diferentes sobre a mesma entidade lógica (E19/DEC-082, "Terceira peça") —
 * mesmo ponto de integração de
 * [br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService.projectBatch] e
 * [br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService.matchAndEnqueue]: chamada
 * por [br.com.bbchain.evex.indexer.adapters.outbound.blockchain.IngestionBatchProcessor] (E4, eventos
 * novos) e [br.com.bbchain.evex.indexer.application.redecode.RedecodeBatchProcessor] (E5, eventos que
 * acabaram de virar `decoded=true`) — **dentro** da mesma transação já aberta por quem chama (assim
 * como os outros 2 serviços, este não toca rede, só banco — não precisa sair da transação de ingestão
 * como o enriquecimento on-chain, [br.com.bbchain.evex.indexer.application.webhook.
 * WebhookEnrichmentResolver], precisou).
 *
 * **Sem `@Transactional` próprio** — mesma razão dos outros 2 serviços do mesmo ponto de integração.
 *
 * **Risco de lock/contenção (confirmado nesta etapa de design)**: [EntityModelRecordPort.mergeFields]
 * grava numa tabela própria (`entity_model_records`), sem overlap de linha com
 * `asset_balance`/`blockchain_event`/`webhook_delivery` — não há contenção **entre** os 3 serviços
 * rodando no mesmo lote (tabelas diferentes, sem lock cruzado). O único risco real é o já aceito e
 * pré-existente pra qualquer upsert em lote dentro de uma transação (`BalanceProjectionService` já
 * corre o mesmo risco hoje): 2 transações concorrentes (ex. ingestão + redecode simultâneos)
 * escrevendo na mesma linha de `entity_model_records` esperam uma pela outra (lock de linha padrão do
 * Postgres), sem introduzir uma classe de deadlock nova — o upsert é sempre 1 linha por
 * `(modelId, correlationValue, network)`, então não há ordem de lock cruzada entre múltiplas linhas
 * dentro da mesma transação que pudesse ciclar contra outra transação.
 *
 * Reaproveita a lição da DEC-035/E7: `try/catch` por evento×contribuidor (não por evento inteiro nem
 * por lote inteiro) — 1 `ModelContributor` mal configurado nunca deve derrubar a transação de
 * ingestão/redecode.
 *
 * Stub de estrutura (E19/DEC-082, passo de design do `hexagonal-architect`) — corpo a implementar por
 * `kotlin-backend-dev`. **Sem `@Service` de propósito, nesta etapa**: `EntityModelPort`/
 * `EntityModelRecordPort` ainda não têm nenhum adapter real registrado como bean — anotar esta classe
 * agora quebraria o carregamento do contexto Spring de qualquer `@SpringBootTest` do projeto inteiro
 * (confirmado rodando a suíte completa nesta sessão: 109 testes falhando com
 * `NoSuchBeanDefinitionException` pra `EntityModelPort`, em testes sem nenhuma relação com a E19).
 * `kotlin-backend-dev` adiciona `@Service` de volta junto com os 2 adapters JPA correspondentes
 * (feito nesta etapa — [br.com.bbchain.evex.indexer.adapters.outbound.persistence.JpaEntityModelAdapter]/
 * [br.com.bbchain.evex.indexer.adapters.outbound.persistence.JpaEntityModelRecordAdapter]).
 */
@Service
class EntityModelProjectionService(
    private val entityModels: EntityModelPort,
    private val records: EntityModelRecordPort,
    private val resolver: CorrelationKeyResolver = CorrelationKeyResolver(),
    indexerProperties: IndexerProperties = IndexerProperties(),
) {
    private val logger = LoggerFactory.getLogger(EntityModelProjectionService::class.java)

    /** WARN deduplicado por `model.id` (DEC-111): chave/campo ausente não pode virar 1 linha por evento. */
    private val warnLogDeduper = WarnLogDeduper(indexerProperties.warnLogDedupeInterval)

    fun projectBatch(events: List<BlockchainEvent>) {
        for (event in events) {
            val eventName = event.eventName
            if (!event.decoded || eventName == null) continue
            val models = entityModels.findActiveContributingTo(event.contractAddress, eventName)
            for (model in models) {
                val contributor = model.contributors.find {
                    it.contractAddress.equals(event.contractAddress, ignoreCase = true) && it.eventName == eventName
                } ?: continue
                projectSafely(model, contributor, event)
            }
        }
    }

    private fun projectSafely(model: EntityModel, contributor: ModelContributor, event: BlockchainEvent) {
        try {
            // Única fonte da chave (DEC-111): o webhook usa o mesmo resolver.
            val correlationValue = when (val result = resolver.resolve(contributor, event)) {
                is CorrelationResult.Resolved -> result.key
                is CorrelationResult.Unresolved -> {
                    warnDeduped(model, contributor, event, "não correlaciona: ${result.reason}")
                    return
                }
            }
            val fields = mutableMapOf<String, Any?>()
            for (mapping in contributor.fieldMappings) {
                when (val value = resolver.resolveFieldValue(mapping, event)) {
                    is FieldValueResult.Present -> fields[mapping.modelField] = value.value
                    is FieldValueResult.Absent ->
                        warnDeduped(model, contributor, event, "campo '${mapping.modelField}' omitido: ${value.reason}")
                }
            }
            records.mergeFields(model.id, correlationValue, event.network, fields)
        } catch (ex: Exception) {
            logger.warn(
                "Falha ao projetar entityModel={} pro evento (contractAddress={}, eventName={}, txHash={}, logIndex={}): {}",
                model.id,
                event.contractAddress,
                event.eventName,
                event.txHash,
                event.logIndex,
                ex.message,
            )
        }
    }

    private fun warnDeduped(model: EntityModel, contributor: ModelContributor, event: BlockchainEvent, message: String) {
        if (!warnLogDeduper.shouldLog(model.id)) return
        logger.warn(
            "entityModel={} (contractAddress={}, eventName={}) {} — evento txHash={}, logIndex={}.",
            model.id,
            contributor.contractAddress,
            contributor.eventName,
            message,
            event.txHash,
            event.logIndex,
        )
    }
}
