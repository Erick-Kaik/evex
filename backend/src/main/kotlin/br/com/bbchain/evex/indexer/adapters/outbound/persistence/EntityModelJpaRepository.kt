package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface EntityModelJpaRepository : JpaRepository<EntityModelEntity, UUID> {
    fun findByNetwork(network: Network, pageable: Pageable): Page<EntityModelEntity>

    /** Usado por [JpaEntityModelAdapter.findActiveContributingTo] — filtro fino de contribuidor em memória. */
    fun findByStatus(status: EntityModelStatus): List<EntityModelEntity>
}
