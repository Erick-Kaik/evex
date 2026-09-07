package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecord
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import org.springframework.stereotype.Component

/** Implementação JPA/Postgres de [EntityModelRecordPort] — ver [EntityModelRecordJpaRepository] pro upsert atômico. */
@Component
class JpaEntityModelRecordAdapter(
    private val repository: EntityModelRecordJpaRepository,
) : EntityModelRecordPort {

    private val objectMapper = jacksonObjectMapper()

    override fun mergeFields(modelId: UUID, correlationValue: String, network: Network, fields: Map<String, Any?>) {
        repository.mergeFields(
            id = UUID.randomUUID(),
            modelId = modelId,
            correlationValue = correlationValue,
            network = network.name,
            data = objectMapper.writeValueAsString(fields),
        )
    }

    override fun findRecord(modelId: UUID, correlationValue: String, network: Network): EntityModelRecord? =
        repository.findByModelIdAndCorrelationValueAndNetwork(modelId, correlationValue, network)?.toDomain()

    private fun EntityModelRecordEntity.toDomain() = EntityModelRecord(
        modelId = modelId,
        correlationValue = correlationValue,
        network = network,
        data = objectMapper.readValue(data, object : TypeReference<Map<String, Any?>>() {}),
        updatedAt = updatedAt,
    )
}
