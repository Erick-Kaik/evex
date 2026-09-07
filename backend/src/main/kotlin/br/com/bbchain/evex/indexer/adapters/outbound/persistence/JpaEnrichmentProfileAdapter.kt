package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [EnrichmentProfilePort]. Decide insert vs. update olhando se já existe
 * uma linha com o mesmo `id` (mesmo padrão de [JpaWebhookConfigAdapter]).
 *
 * `calls` (de)serializado manualmente via um `ObjectMapper` Jackson 2 **local** com `KotlinModule`
 * ([jacksonObjectMapper]) — mesmo motivo de [JpaWebhookConfigAdapter] (`EnrichmentCall` é uma data
 * class Kotlin com construtor primário sem construtor sem-argumento).
 */
@Component
class JpaEnrichmentProfileAdapter(
    private val repository: EnrichmentProfileJpaRepository,
) : EnrichmentProfilePort {

    private val objectMapper = jacksonObjectMapper()

    override fun save(profile: EnrichmentProfile): EnrichmentProfile {
        val alreadyPersisted = repository.existsById(profile.id)
        val entity = profile.toEntity(isNew = !alreadyPersisted)
        return repository.save(entity).toDomain()
    }

    override fun findById(id: UUID): EnrichmentProfile? = repository.findById(id).map { it.toDomain() }.orElse(null)

    override fun findAllByIds(ids: List<UUID>): List<EnrichmentProfile> = repository.findAllById(ids).map { it.toDomain() }

    override fun findAll(network: Network?, page: Int, size: Int): PageResult<EnrichmentProfile> {
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

    private fun EnrichmentProfile.toEntity(isNew: Boolean) = EnrichmentProfileEntity(
        id = id,
        name = name,
        description = description,
        network = network,
        status = status,
        calls = objectMapper.writeValueAsString(calls),
        createdAt = createdAt,
        new = isNew,
    )

    private fun EnrichmentProfileEntity.toDomain() = EnrichmentProfile(
        id = getId(),
        name = name,
        description = description,
        network = network,
        status = status,
        calls = objectMapper.readValue(calls, object : TypeReference<List<EnrichmentCall>>() {}),
        createdAt = createdAt,
    )
}
