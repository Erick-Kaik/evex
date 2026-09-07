package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
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
 * Entidade JPA do agregado Modelo de Entidade (`entity_models`, E19/DEC-082) — só o CRUD do agregado
 * (o registro acumulado é [EntityModelRecordEntity], tabela separada). Mesmo padrão
 * `Persistable<UUID>` de [EnrichmentProfileEntity] (DEC-018).
 *
 * `contributors` é JSONB (`@JdbcTypeCode(SqlTypes.JSON)`), mapeado como `String` — mesmo padrão de
 * `EnrichmentProfileEntity.calls`. [ContributingEntityModelPort.findActiveContributingTo] filtra em
 * memória (carrega todos os `ACTIVE`, casa contra `contributors` já desserializado) — catálogo pequeno
 * por natureza (uma instância do Evex por rede/cliente), decisão documentada no roadmap E19.
 */
@Entity
@Table(name = "entity_models")
class EntityModelEntity(
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
    var status: EntityModelStatus,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var contributors: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
