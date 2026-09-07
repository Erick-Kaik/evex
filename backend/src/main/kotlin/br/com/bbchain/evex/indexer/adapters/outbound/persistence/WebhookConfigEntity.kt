package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
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
 * Entidade JPA de configuração de webhook (`webhook_configs`, doc §4.6). Mesmo padrão
 * `Persistable<UUID>` de [BalanceMonitorConfigEntity] (`id` gerado em `application`, não pelo banco —
 * DEC-018): `id` privado + `getId()` explícito, flag `new` transiente setada pelo adapter via
 * `existsById`.
 *
 * `filters` é JSONB (`@JdbcTypeCode(SqlTypes.JSON)`), mas mapeado como `String` (JSON já serializado)
 * — mesmo padrão de `BlockchainEventEntity.raw_topics`/`decoded_parameters` (DEC-021/DEC-024): o
 * (de)serialize acontece explicitamente em [JpaWebhookConfigAdapter] (Jackson 2 local, DEC-020).
 *
 * `enrichmentProfileIds`/`entityModelIds` (E19/DEC-082) são JSONB pelo mesmo motivo — arrays de
 * `UUID`, ordem preservada (significativa pra `enrichmentProfileIds`, ver
 * `br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort.findAllByIds`).
 */
@Entity
@Table(
    name = "webhook_configs",
    indexes = [Index(name = "idx_webhook_configs_lookup", columnList = "contract_address,event_name,active")],
)
class WebhookConfigEntity(
    @Id
    private val id: UUID,
    @Column(name = "contract_address", nullable = false, length = 42)
    val contractAddress: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Column(name = "event_name", nullable = false)
    val eventName: String,
    @Column(nullable = false, columnDefinition = "text")
    val url: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    val filters: String,
    @Column(name = "payload_template", columnDefinition = "text")
    val payloadTemplate: String? = null,
    @Column(nullable = false)
    var active: Boolean,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enrichment_profile_ids", nullable = false)
    var enrichmentProfileIds: String = "[]",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entity_model_ids", nullable = false)
    var entityModelIds: String = "[]",
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    // `id` privado por causa do choque de assinatura JVM entre `override val id` e `getId()` —
    // mesma explicação de MonitoredContractEntity (DEC-018).
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
