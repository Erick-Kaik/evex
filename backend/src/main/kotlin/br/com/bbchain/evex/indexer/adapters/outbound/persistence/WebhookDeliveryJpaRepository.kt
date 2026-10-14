package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WebhookDeliveryJpaRepository : JpaRepository<WebhookDeliveryEntity, UUID>, JpaSpecificationExecutor<WebhookDeliveryEntity> {

    @Query(
        """
            SELECT d FROM WebhookDeliveryEntity d
            WHERE d.status = :status AND (d.nextAttemptAt IS NULL OR d.nextAttemptAt <= :now)
            ORDER BY CASE WHEN d.origin = 'MATCH' THEN 0 ELSE 1 END ASC, d.createdAt ASC
        """,
    )
    fun findDue(@Param("status") status: WebhookDeliveryStatus, @Param("now") now: Instant, pageable: Pageable): List<WebhookDeliveryEntity>

    @Query(
        """
            SELECT COUNT(d) > 0 FROM WebhookDeliveryEntity d
            WHERE d.webhookConfigId = :webhookConfigId AND d.blockchainEventId = :blockchainEventId
              AND d.status = :status AND d.origin <> 'MATCH'
        """,
    )
    fun existsPendingResend(
        @Param("webhookConfigId") webhookConfigId: UUID,
        @Param("blockchainEventId") blockchainEventId: UUID,
        @Param("status") status: WebhookDeliveryStatus,
    ): Boolean

    /**
     * INSERT nativo `ON CONFLICT DO NOTHING` contra o índice único parcial `uq_webhook_deliveries_pending_resend`
     * (V8): perde a corrida sem erro e sem marcar a transação como rollback-only. Devolve 1 (gravou) ou 0 (já havia
     * pendente). `CAST` em todo parâmetro anulável (o Postgres não infere o tipo de `NULL` solto).
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO webhook_deliveries (id, webhook_config_id, blockchain_event_id, network, payload, status,
                attempt_count, next_attempt_at, created_at, block_number, decoded_parameters, origin,
                enrichment_block_mode, enrichment_block_number, target_url_override, model_state_at)
            VALUES (:id, :webhookConfigId, :blockchainEventId, :network, :payload, :status,
                :attemptCount, CAST(:nextAttemptAt AS timestamptz), :createdAt, CAST(:blockNumber AS bigint),
                CAST(:decodedParameters AS jsonb), :origin, CAST(:enrichmentBlockMode AS varchar),
                CAST(:enrichmentBlockNumber AS bigint), CAST(:targetUrlOverride AS text), CAST(:modelStateAt AS timestamptz))
            ON CONFLICT (webhook_config_id, blockchain_event_id) WHERE status = 'PENDING' AND origin <> 'MATCH' DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertResendIfAbsent(
        @Param("id") id: UUID,
        @Param("webhookConfigId") webhookConfigId: UUID,
        @Param("blockchainEventId") blockchainEventId: UUID,
        @Param("network") network: String,
        @Param("payload") payload: String,
        @Param("status") status: String,
        @Param("attemptCount") attemptCount: Int,
        @Param("nextAttemptAt") nextAttemptAt: Instant?,
        @Param("createdAt") createdAt: Instant,
        @Param("blockNumber") blockNumber: Long?,
        @Param("decodedParameters") decodedParameters: String?,
        @Param("origin") origin: String,
        @Param("enrichmentBlockMode") enrichmentBlockMode: String?,
        @Param("enrichmentBlockNumber") enrichmentBlockNumber: Long?,
        @Param("targetUrlOverride") targetUrlOverride: String?,
        @Param("modelStateAt") modelStateAt: Instant?,
    ): Int

    @Query("SELECT COUNT(d) FROM WebhookDeliveryEntity d WHERE d.status = :status AND d.origin <> 'MATCH'")
    fun countResendByStatus(@Param("status") status: WebhookDeliveryStatus): Long
}
