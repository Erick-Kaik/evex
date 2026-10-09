package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.EntityModelsApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.CreateEntityModelPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EntityModel as EntityModelDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EntityModelPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EntityModelStatus as EntityModelStatusDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ModelCorrelationKeyPart as CorrelationKeyPartDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ModelContributor as ModelContributorDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ModelFieldMapping as ModelFieldMappingDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateEntityModelPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateEntityModelStatusPayload
import br.com.bbchain.evex.indexer.application.model.EntityModelService
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyPart
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Implementação fina de [EntityModelsApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [EntityModelService]. Nenhuma regra de negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class EntityModelsApiServiceImpl(
    private val entityModelService: EntityModelService,
) : EntityModelsApiService {

    override fun createEntityModel(createEntityModelPayload: CreateEntityModelPayload): EntityModelDto {
        val model = entityModelService.register(
            name = createEntityModelPayload.name,
            description = createEntityModelPayload.description,
            network = createEntityModelPayload.network.toDomain(),
            contributors = createEntityModelPayload.contributors.map { it.toDomain() },
        )
        return model.toDto()
    }

    override fun getEntityModel(id: UUID): EntityModelDto = entityModelService.getById(id).toDto()

    override fun listEntityModels(network: NetworkDto?, page: kotlin.Int, size: kotlin.Int): EntityModelPage {
        val result = entityModelService.list(network?.toDomain(), page, size)
        return EntityModelPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    override fun updateEntityModel(id: UUID, updateEntityModelPayload: UpdateEntityModelPayload): EntityModelDto {
        val model = entityModelService.update(
            id = id,
            name = updateEntityModelPayload.name,
            description = updateEntityModelPayload.description,
            contributors = updateEntityModelPayload.contributors.map { it.toDomain() },
        )
        return model.toDto()
    }

    override fun updateEntityModelStatus(id: UUID, updateEntityModelStatusPayload: UpdateEntityModelStatusPayload): EntityModelDto =
        entityModelService.updateStatus(id, updateEntityModelStatusPayload.status.toDomain()).toDto()

    private fun NetworkDto.toDomain(): Network = Network.valueOf(this.name)

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    private fun EntityModelStatusDto.toDomain(): EntityModelStatus = EntityModelStatus.valueOf(this.name)

    private fun EntityModelStatus.toDto(): EntityModelStatusDto = EntityModelStatusDto.valueOf(this.name)

    private fun ModelFieldMappingDto.toDomain(): ModelFieldMapping = ModelFieldMapping(decodedParam = decodedParam, modelField = modelField, source = source ?: "PARAM")

    private fun ModelFieldMapping.toDto(): ModelFieldMappingDto = ModelFieldMappingDto(decodedParam = decodedParam, modelField = modelField, source = source)

    private fun CorrelationKeyPartDto.toDomain(): CorrelationKeyPart = CorrelationKeyPart(type = type, arg = arg)

    private fun CorrelationKeyPart.toDto(): CorrelationKeyPartDto = CorrelationKeyPartDto(type = type, arg = arg)

    private fun ModelContributorDto.toDomain(): ModelContributor = ModelContributor(
        contractAddress = contractAddress,
        eventName = eventName,
        correlationParam = correlationParam,
        fieldMappings = fieldMappings.map { it.toDomain() },
        correlationKey = correlationKey?.map { it.toDomain() },
    )

    private fun ModelContributor.toDto(): ModelContributorDto = ModelContributorDto(
        contractAddress = contractAddress,
        eventName = eventName,
        correlationParam = correlationParam,
        correlationKey = correlationKey?.map { it.toDto() }?.toMutableList(),
        fieldMappings = fieldMappings.map { it.toDto() }.toMutableList(),
    )

    private fun EntityModel.toDto(): EntityModelDto = EntityModelDto(
        id = id,
        name = name,
        description = description,
        network = network.toDto(),
        status = status.toDto(),
        contributors = contributors.map { it.toDto() }.toMutableList(),
        createdAt = createdAt.atOffset(ZoneOffset.UTC),
    )
}
