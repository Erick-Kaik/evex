package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de uma entrega enfileirada de webhook (`webhook_deliveries`). Mesmo padrão
 * `Persistable<UUID>` de [WebhookConfigEntity] (DEC-018) — **atualizada in-place via
 * `repository.save()` normal**: diferente de `AssetBalanceEntity`/E7 (upsert por chave natural), aqui
 * não há chave natural — é sempre update-por-PK-já-conhecida (`enqueue` cria, `WebhookDeliveryRecorder`
 * atualiza status/tentativas), então o padrão `Persistable` padrão serve sem desvio.
 *
 * `blockNumber`/`decodedParameters` (E19/DEC-082) — snapshot do evento tirado no `enqueue` (Fase 1),
 * nullable (entregas persistidas antes da E19 não têm). `decodedParameters` é JSONB, mesmo padrão de
 * `BlockchainEventEntity.decoded_parameters`.
 */
@Entity
@Table(
    name = "webhook_deliveries",
    indexes = [
        Index(name = "idx_webhook_deliveries_due", columnList = "status,next_attempt_at"),
        Index(name = "idx_webhook_deliveries_config", columnList = "webhook_config_id"),
    ],
)
class WebhookDeliveryEntity(
    @Id
    private val id: UUID,
    @Column(name = "webhook_config_id", nullable = false)
    val webhookConfigId: UUID,
    @Column(name = "blockchain_event_id", nullable = false)
    val blockchainEventId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Column(nullable = false, columnDefinition = "text")
    val payload: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val status: WebhookDeliveryStatus,
    @Column(name = "attempt_count", nullable = false)
    val attemptCount: Int,
    @Column(name = "next_attempt_at")
    val nextAttemptAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "block_number")
    val blockNumber: Long? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decoded_parameters")
    val decodedParameters: String? = null,
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
