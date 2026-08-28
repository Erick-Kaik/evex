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
) : Persistable<UUID> {
    override fun getId(): UUID = id

    override fun isNew(): Boolean = true
}
