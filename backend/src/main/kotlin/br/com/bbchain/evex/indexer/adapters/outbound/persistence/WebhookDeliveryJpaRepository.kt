package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WebhookDeliveryJpaRepository : JpaRepository<WebhookDeliveryEntity, UUID>, JpaSpecificationExecutor<WebhookDeliveryEntity> {

    @Query(
        """
            SELECT d FROM WebhookDeliveryEntity d
            WHERE d.status = :status AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)
            ORDER BY d.createdAt ASC
        """,
    )
    fun findDue(@Param("status") status: WebhookDeliveryStatus, @Param("now") now: Instant, pageable: Pageable): List<WebhookDeliveryEntity>
}
