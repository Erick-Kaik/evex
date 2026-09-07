package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/**
 * Entidade JPA do registro acumulado de uma entidade lógica (`entity_model_records`, E19/DEC-082).
 * **Sem `Persistable<UUID>`** (mesmo desvio deliberado de [AssetBalanceEntity]) — toda escrita passa
 * por `@Modifying @Query` nativa com `ON CONFLICT` ([EntityModelRecordJpaRepository.mergeFields]),
 * nunca por `repository.save()`; esta entidade só é usada para **leitura**
 * ([EntityModelRecordJpaRepository.findByModelIdAndCorrelationValueAndNetwork]).
 *
 * `data` é JSONB (`@JdbcTypeCode(SqlTypes.JSON)`), mapeado como `String` — mesmo padrão de
 * `EnrichmentProfileEntity.calls`.
 */
@Entity
@Table(
    name = "entity_model_records",
    uniqueConstraints = [UniqueConstraint(columnNames = ["model_id", "correlation_value", "network"])],
)
class EntityModelRecordEntity(
    @Id
    val id: UUID,
    @Column(name = "model_id", nullable = false)
    val modelId: UUID,
    @Column(name = "correlation_value", nullable = false)
    val correlationValue: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    val data: String,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)
