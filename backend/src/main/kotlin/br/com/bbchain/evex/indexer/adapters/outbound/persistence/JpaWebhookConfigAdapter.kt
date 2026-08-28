package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [WebhookConfigPort]. Decide insert vs. update olhando se já existe uma
 * linha com o mesmo `id` (mesmo padrão de [JpaBalanceMonitorConfigAdapter]).
 *
 * `filters` (de)serializado manualmente via um `ObjectMapper` Jackson 2 **local** (não injetado —
 * DEC-020), mesmo padrão de [JpaBlockchainEventAdapter] — mas com [jacksonObjectMapper] (registra
 * `KotlinModule`) em vez de `ObjectMapper()` puro: diferente de `rawTopics`/`decodedParameters`
 * (`List<String>`/`Map<String, Any?>`, tipos genéricos que Jackson já sabe instanciar sem ajuda),
 * `filters` é `List<WebhookFilterCondition>` — uma data class Kotlin com construtor primário sem
 * construtor sem-argumento; sem `KotlinModule`, a desserialização falha com
 * `InvalidDefinitionException` (achado real de teste, primeira vez que este projeto desserializa uma
 * data class própria via Jackson 2 local em vez de só `Map`/`List` genéricos).
 */
@Component
class JpaWebhookConfigAdapter(
    private val repository: WebhookConfigJpaRepository,
) : WebhookConfigPort {

    private val objectMapper = jacksonObjectMapper()

    override fun save(config: WebhookConfig): WebhookConfig {
        val alreadyPersisted = repository.existsById(config.id)
        val entity = config.toEntity(isNew = !alreadyPersisted)
        return repository.save(entity).toDomain()
    }

    override fun findById(id: UUID): WebhookConfig? = repository.findById(id).map { it.toDomain() }.orElse(null)

    override fun findActive(contractAddress: String, eventName: String): List<WebhookConfig> =
        repository.findByContractAddressAndEventNameAndActiveTrue(contractAddress.lowercase(), eventName).map { it.toDomain() }

    override fun findAll(page: Int, size: Int): PageResult<WebhookConfig> {
        val pageable = PageRequest.of(page, size, Sort.by("createdAt"))
        val result = repository.findAll(pageable)
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    private fun WebhookConfig.toEntity(isNew: Boolean) = WebhookConfigEntity(
        id = id,
        contractAddress = contractAddress,
        network = network,
        eventName = eventName,
        url = url,
        filters = objectMapper.writeValueAsString(filters),
        payloadTemplate = payloadTemplate,
        active = active,
        createdAt = createdAt,
        new = isNew,
    )

    private fun WebhookConfigEntity.toDomain() = WebhookConfig(
        id = getId(),
        contractAddress = contractAddress,
        network = network,
        eventName = eventName,
        url = url,
        filters = objectMapper.readValue(filters, object : TypeReference<List<WebhookFilterCondition>>() {}),
        payloadTemplate = payloadTemplate,
        active = active,
        createdAt = createdAt,
    )
}
