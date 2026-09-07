package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de Perfil de Enriquecimento (`enrichment_profiles`, E19/DEC-082). Mesmo padrão
 * `Persistable<UUID>` de [WebhookConfigEntity]/[WalletEntity] (`id` gerado em `application`, não pelo
 * banco — DEC-018).
 *
 * `calls` é JSONB (`@JdbcTypeCode(SqlTypes.JSON)`), mapeado como `String` (JSON já serializado) —
 * mesmo padrão de `WebhookConfigEntity.filters` (DEC-021/DEC-024): o (de)serialize acontece
 * explicitamente em [JpaEnrichmentProfileAdapter] (Jackson 2 com `KotlinModule`, mesmo motivo de
 * `JpaWebhookConfigAdapter` — `EnrichmentCall` é uma data class Kotlin, não um `Map`/`List` genérico).
 */
@Entity
@Table(name = "enrichment_profiles")
class EnrichmentProfileEntity(
    @Id
    private val id: UUID,
    @Column(nullable = false)
    var name: String,
    @Column
    var description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: EnrichmentProfileStatus,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var calls: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    // `id` privado por causa do choque de assinatura JVM entre `override val id` e `getId()` — mesma
    // explicação de MonitoredContractEntity (DEC-018).
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
