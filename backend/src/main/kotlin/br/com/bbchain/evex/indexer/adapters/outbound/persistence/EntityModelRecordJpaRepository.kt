package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * [mergeFields] é `@Modifying` nativa com `ON CONFLICT` (mesmo padrão de
 * [AssetBalanceJpaRepository.upsertDelta], DEC-082 item 4) — nunca `repository.save()` nesta tabela.
 * `clearAutomatically = true` pelo mesmo motivo documentado ali: consultas nativas passam por baixo do
 * Hibernate, sem isso um `findByModelIdAndCorrelationValueAndNetwork` que já tivesse carregado a mesma
 * linha antes (mesma transação) devolveria o objeto cacheado desatualizado.
 *
 * `CAST(:data AS jsonb)`: o parâmetro chega ao driver JDBC tipado como `varchar`, sem cast explícito o
 * Postgres rejeita a atribuição direta a uma coluna `jsonb` (só literais/`unknown` têm cast de
 * atribuição implícito).
 */
interface EntityModelRecordJpaRepository : JpaRepository<EntityModelRecordEntity, UUID> {

    fun findByModelIdAndCorrelationValueAndNetwork(modelId: UUID, correlationValue: String, network: Network): EntityModelRecordEntity?

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        value = """
            INSERT INTO entity_model_records (id, model_id, correlation_value, network, data, updated_at)
            VALUES (:id, :modelId, :correlationValue, :network, CAST(:data AS jsonb), now())
            ON CONFLICT (model_id, correlation_value, network)
            DO UPDATE SET data = entity_model_records.data || EXCLUDED.data, updated_at = now()
        """,
        nativeQuery = true,
    )
    fun mergeFields(
        @Param("id") id: UUID,
        @Param("modelId") modelId: UUID,
        @Param("correlationValue") correlationValue: String,
        @Param("network") network: String,
        @Param("data") data: String,
    )
}
