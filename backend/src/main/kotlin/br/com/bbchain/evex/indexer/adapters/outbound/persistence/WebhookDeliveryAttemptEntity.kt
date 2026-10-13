package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de 1 tentativa de entrega de webhook (`webhook_delivery_attempts`) — sempre `new=true`
 * (só insert, nunca update, diferente de [WebhookDeliveryEntity]): o log de tentativas é append-only.
 */
@Entity
@Table(name = "webhook_delivery_attempts", indexes = [Index(name = "idx_webhook_delivery_attempts_delivery", columnList = "delivery_id")])
class WebhookDeliveryAttemptEntity(
    @Id
    private val id: UUID,
    @Column(name = "delivery_id", nullable = false)
    val deliveryId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Column(name = "attempt_number", nullable = false)
    val attemptNumber: Int,
    @Column(name = "attempted_at", nullable = false)
    val attemptedAt: Instant,
    @Column(nullable = false)
    val success: Boolean,
    @Column(name = "http_status")
    val httpStatus: Int? = null,
    @Column(name = "response_body", columnDefinition = "text")
    val responseBody: String? = null,
    @Column(name = "error_message", columnDefinition = "text")
    val errorMessage: String? = null,
    /** E33: lista de resultados por chamada de enriquecimento (JSON já serializado, ver [JpaWebhookDeliveryAdapter]). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enrichment_results")
    val enrichmentResults: String? = null,
    @Column(name = "enrichment_block_used", length = 32)
    val enrichmentBlockUsed: String? = null,
    @Column(name = "sent_payload", columnDefinition = "text")
    val sentPayload: String? = null,
) : Persistable<UUID> {
    override fun getId(): UUID = id

    override fun isNew(): Boolean = true
}
