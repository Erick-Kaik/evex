package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [WebhookDeliveryPort]. `decodedParameters` (de)serializado
 * manualmente via `ObjectMapper` Jackson 2 local (DEC-020) — mesmo padrão de
 * `JpaBlockchainEventAdapter.decodedParameters` (`Map<String, Any?>`, tipo genérico, sem necessidade
 * de `KotlinModule`).
 */
@Component
class JpaWebhookDeliveryAdapter(
    private val repository: WebhookDeliveryJpaRepository,
    private val attemptRepository: WebhookDeliveryAttemptJpaRepository,
) : WebhookDeliveryPort {

    private val objectMapper = ObjectMapper()

    override fun enqueue(delivery: WebhookDelivery): WebhookDelivery = repository.save(delivery.toEntity(isNew = true)).toDomain()

    override fun findDue(limit: Int): List<WebhookDelivery> =
        repository.findDue(WebhookDeliveryStatus.PENDING, Instant.now(), PageRequest.of(0, limit)).map { it.toDomain() }

    override fun save(delivery: WebhookDelivery): WebhookDelivery {
        val alreadyPersisted = repository.existsById(delivery.id)
        return repository.save(delivery.toEntity(isNew = !alreadyPersisted)).toDomain()
    }

    override fun recordAttempt(attempt: WebhookDeliveryAttempt): WebhookDeliveryAttempt = attemptRepository.save(attempt.toEntity()).toDomain()

    override fun findAttempts(deliveryId: UUID): List<WebhookDeliveryAttempt> =
        attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId).map { it.toDomain() }

    override fun search(webhookConfigId: UUID?, status: WebhookDeliveryStatus?, page: Int, size: Int): PageResult<WebhookDelivery> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val specification = Specification.allOf(
            listOfNotNull(
                webhookConfigId?.let { id -> Specification<WebhookDeliveryEntity> { root, _, cb -> cb.equal(root.get<UUID>("webhookConfigId"), id) } },
                status?.let { s -> Specification<WebhookDeliveryEntity> { root, _, cb -> cb.equal(root.get<WebhookDeliveryStatus>("status"), s) } },
            ),
        )
        val result = repository.findAll(specification, pageable)
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    private fun WebhookDelivery.toEntity(isNew: Boolean) = WebhookDeliveryEntity(
        id = id,
        webhookConfigId = webhookConfigId,
        blockchainEventId = blockchainEventId,
        network = network,
        payload = payload,
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        blockNumber = blockNumber,
        decodedParameters = decodedParameters?.let { objectMapper.writeValueAsString(it) },
        new = isNew,
    )

    private fun WebhookDeliveryEntity.toDomain() = WebhookDelivery(
        id = getId(),
        webhookConfigId = webhookConfigId,
        blockchainEventId = blockchainEventId,
        network = network,
        payload = payload,
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        blockNumber = blockNumber,
        decodedParameters = decodedParameters?.let { objectMapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) },
    )

    private fun WebhookDeliveryAttempt.toEntity() = WebhookDeliveryAttemptEntity(
        id = id,
        deliveryId = deliveryId,
        network = network,
        attemptNumber = attemptNumber,
        attemptedAt = attemptedAt,
        success = success,
        httpStatus = httpStatus,
        responseBody = responseBody,
        errorMessage = errorMessage,
    )

    private fun WebhookDeliveryAttemptEntity.toDomain() = WebhookDeliveryAttempt(
        id = getId(),
        deliveryId = deliveryId,
        network = network,
        attemptNumber = attemptNumber,
        attemptedAt = attemptedAt,
        success = success,
        httpStatus = httpStatus,
        responseBody = responseBody,
        errorMessage = errorMessage,
    )
}
