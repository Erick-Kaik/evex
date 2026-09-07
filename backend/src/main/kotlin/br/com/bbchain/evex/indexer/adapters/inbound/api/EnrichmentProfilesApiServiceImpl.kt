package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.EnrichmentProfilesApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.CreateEnrichmentProfilePayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentCall as EnrichmentCallDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentOutputMapping as EnrichmentOutputMappingDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentProfile as EnrichmentProfileDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentProfilePage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EnrichmentProfileStatus as EnrichmentProfileStatusDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateEnrichmentProfilePayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateEnrichmentProfileStatusPayload
import br.com.bbchain.evex.indexer.application.enrichment.EnrichmentProfileService
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentOutputMapping
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Implementação fina de [EnrichmentProfilesApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [EnrichmentProfileService]. Nenhuma regra de negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class EnrichmentProfilesApiServiceImpl(
    private val enrichmentProfileService: EnrichmentProfileService,
) : EnrichmentProfilesApiService {

    override fun createEnrichmentProfile(createEnrichmentProfilePayload: CreateEnrichmentProfilePayload): EnrichmentProfileDto {
        val profile = enrichmentProfileService.register(
            name = createEnrichmentProfilePayload.name,
            description = createEnrichmentProfilePayload.description,
            network = createEnrichmentProfilePayload.network.toDomain(),
            calls = createEnrichmentProfilePayload.calls.map { it.toDomain() },
        )
        return profile.toDto()
    }

    override fun getEnrichmentProfile(id: UUID): EnrichmentProfileDto = enrichmentProfileService.getById(id).toDto()

    override fun listEnrichmentProfiles(network: NetworkDto?, page: kotlin.Int, size: kotlin.Int): EnrichmentProfilePage {
        val result = enrichmentProfileService.list(network?.toDomain(), page, size)
        return EnrichmentProfilePage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    override fun updateEnrichmentProfile(id: UUID, updateEnrichmentProfilePayload: UpdateEnrichmentProfilePayload): EnrichmentProfileDto {
        val profile = enrichmentProfileService.update(
            id = id,
            name = updateEnrichmentProfilePayload.name,
            description = updateEnrichmentProfilePayload.description,
            calls = updateEnrichmentProfilePayload.calls.map { it.toDomain() },
        )
        return profile.toDto()
    }

    override fun updateEnrichmentProfileStatus(
        id: UUID,
        updateEnrichmentProfileStatusPayload: UpdateEnrichmentProfileStatusPayload,
    ): EnrichmentProfileDto =
        enrichmentProfileService.updateStatus(id, updateEnrichmentProfileStatusPayload.status.toDomain()).toDto()

    private fun NetworkDto.toDomain(): Network = Network.valueOf(this.name)

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    private fun EnrichmentProfileStatusDto.toDomain(): EnrichmentProfileStatus = EnrichmentProfileStatus.valueOf(this.name)

    private fun EnrichmentProfileStatus.toDto(): EnrichmentProfileStatusDto = EnrichmentProfileStatusDto.valueOf(this.name)

    private fun EnrichmentCallDto.toDomain(): EnrichmentCall = EnrichmentCall(
        targetContractAddress = targetContractAddress,
        methodName = methodName,
        args = args,
        outputs = outputs.map { it.toDomain() },
    )

    private fun EnrichmentCall.toDto(): EnrichmentCallDto = EnrichmentCallDto(
        targetContractAddress = targetContractAddress,
        methodName = methodName,
        args = args.toMutableList(),
        outputs = outputs.map { it.toDto() }.toMutableList(),
    )

    private fun EnrichmentOutputMappingDto.toDomain(): EnrichmentOutputMapping = EnrichmentOutputMapping(
        outputIndex = outputIndex,
        placeholder = placeholder,
    )

    private fun EnrichmentOutputMapping.toDto(): EnrichmentOutputMappingDto = EnrichmentOutputMappingDto(
        outputIndex = outputIndex,
        placeholder = placeholder,
    )

    private fun EnrichmentProfile.toDto(): EnrichmentProfileDto = EnrichmentProfileDto(
        id = id,
        name = name,
        description = description,
        network = network.toDto(),
        status = status.toDto(),
        calls = calls.map { it.toDto() }.toMutableList(),
        createdAt = createdAt.atOffset(ZoneOffset.UTC),
    )
}
