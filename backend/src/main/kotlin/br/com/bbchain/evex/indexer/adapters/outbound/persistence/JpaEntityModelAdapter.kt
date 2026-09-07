package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [EntityModelPort] (o CRUD do agregado — o registro acumulado é
 * [JpaEntityModelRecordAdapter]). Mesmo padrão de [JpaEnrichmentProfileAdapter].
 *
 * [findActiveContributingTo]: carrega todos os `ACTIVE` (catálogo pequeno por natureza) e filtra em
 * memória por `(contractAddress, eventName)` batendo com algum `ModelContributor` — decisão explícita
 * do roadmap E19 (nem toda expressão JSONB compensa virar JPQL nativo pra um catálogo desse tamanho).
 */
@Component
class JpaEntityModelAdapter(
    private val repository: EntityModelJpaRepository,
) : EntityModelPort {

    private val objectMapper = jacksonObjectMapper()

    override fun save(model: EntityModel): EntityModel {
        val alreadyPersisted = repository.existsById(model.id)
        val entity = model.toEntity(isNew = !alreadyPersisted)
        return repository.save(entity).toDomain()
    }

    override fun findById(id: UUID): EntityModel? = repository.findById(id).map { it.toDomain() }.orElse(null)

    override fun findActiveContributingTo(contractAddress: String, eventName: String): List<EntityModel> {
        val normalizedAddress = contractAddress.lowercase()
        return repository.findByStatus(EntityModelStatus.ACTIVE)
            .map { it.toDomain() }
            .filter { model -> model.contributors.any { it.contractAddress.lowercase() == normalizedAddress && it.eventName == eventName } }
    }

    override fun findAllByIds(ids: List<UUID>): List<EntityModel> = repository.findAllById(ids).map { it.toDomain() }

    override fun findAll(network: Network?, page: Int, size: Int): PageResult<EntityModel> {
        val pageable = PageRequest.of(page, size, Sort.by("createdAt"))
        val result = if (network != null) {
            repository.findByNetwork(network, pageable)
        } else {
            repository.findAll(pageable)
        }
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    private fun EntityModel.toEntity(isNew: Boolean) = EntityModelEntity(
        id = id,
        name = name,
        description = description,
        network = network,
        status = status,
        contributors = objectMapper.writeValueAsString(contributors),
        createdAt = createdAt,
        new = isNew,
    )

    private fun EntityModelEntity.toDomain() = EntityModel(
        id = getId(),
        name = name,
        description = description,
        network = network,
        status = status,
        contributors = objectMapper.readValue(contributors, object : TypeReference<List<ModelContributor>>() {}),
        createdAt = createdAt,
    )
}
