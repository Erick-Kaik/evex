package br.com.bbchain.evex.indexer.domain.model

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.util.UUID

/**
 * Porta de saída para persistência/consulta de [EntityModel] (o CRUD, não o registro acumulado — esse
 * é [EntityModelRecordPort]). Mirror estrutural de
 * [br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort]. Implementação JPA/Postgres em
 * `adapters/outbound/persistence/JpaEntityModelAdapter`.
 */
interface EntityModelPort {
    fun save(model: EntityModel): EntityModel

    fun findById(id: UUID): EntityModel?

    /**
     * Todos os [EntityModel] `ACTIVE` que têm ao menos 1 [ModelContributor] casando com
     * [contractAddress]/[eventName] — consultada por
     * [br.com.bbchain.evex.indexer.application.model.EntityModelProjectionService] a cada evento
     * decodificado (mesmo papel de
     * [br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort.findActive], mas aqui o
     * casamento é contra um contribuinte de dentro da lista, não contra o agregado inteiro).
     */
    fun findActiveContributingTo(contractAddress: String, eventName: String): List<EntityModel>

    /**
     * Busca em lote, sem garantia de ordem — usada pela Fase 1 do render de webhook
     * ([br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService], E19/DEC-082) pra
     * resolver `{{model.*}}` a partir de `WebhookConfig.entityModelIds`. Mesmo contrato de
     * [br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort.findAllByIds].
     */
    fun findAllByIds(ids: List<UUID>): List<EntityModel>

    fun findAll(network: Network?, page: Int, size: Int): PageResult<EntityModel>
}
