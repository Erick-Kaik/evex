package br.com.bbchain.evex.indexer.domain.enrichment

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.util.UUID

/**
 * Porta de saída para persistência/consulta de [EnrichmentProfile]. Mirror estrutural de
 * [br.com.bbchain.evex.indexer.domain.wallet.WalletPort]/
 * [br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort] — implementação JPA/Postgres em
 * `adapters/outbound/persistence/JpaEnrichmentProfileAdapter`. Update/patch de status não têm método
 * próprio — `application` faz "load via findById -> aplica mudança -> save()", igual contrato/carteira.
 */
interface EnrichmentProfilePort {
    fun save(profile: EnrichmentProfile): EnrichmentProfile

    fun findById(id: UUID): EnrichmentProfile?

    /**
     * Busca em lote, **sem garantia de ordem** de retorno (comportamento padrão de `findAllById` do
     * Spring Data) — usada pela resolução ao vivo no dispatch
     * ([br.com.bbchain.evex.indexer.application.webhook.WebhookEnrichmentResolver], E19/DEC-082) pra
     * buscar todos os perfis de `WebhookConfig.enrichmentProfileIds` de uma vez; quem chama é
     * responsável por reordenar o resultado pela ordem original de [ids] (a ordem de resolução das
     * chamadas de todos os perfis é significativa — encadeamento via `{{enriched.*}}`).
     */
    fun findAllByIds(ids: List<UUID>): List<EnrichmentProfile>

    fun findAll(network: Network?, page: Int, size: Int): PageResult<EnrichmentProfile>
}
