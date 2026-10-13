package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryOrigin
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

    override fun enqueueResendIfAbsent(delivery: WebhookDelivery): WebhookDelivery? {
        require(delivery.origin != WebhookDeliveryOrigin.MATCH) { "enqueueResendIfAbsent é só para reenvios" }
        require(delivery.status == WebhookDeliveryStatus.PENDING) { "reenvio é enfileirado como PENDING" }
        val inserted = repository.insertResendIfAbsent(
            id = delivery.id,
            webhookConfigId = delivery.webhookConfigId,
            blockchainEventId = delivery.blockchainEventId,
            network = delivery.network.name,
            payload = delivery.payload,
            status = delivery.status.name,
            attemptCount = delivery.attemptCount,
            nextAttemptAt = delivery.nextAttemptAt,
            createdAt = delivery.createdAt,
            blockNumber = delivery.blockNumber,
            decodedParameters = delivery.decodedParameters?.let { objectMapper.writeValueAsString(it) },
            origin = delivery.origin,
            enrichmentBlockMode = delivery.enrichmentBlockMode,
            enrichmentBlockNumber = delivery.enrichmentBlockNumber,
            targetUrlOverride = delivery.targetUrlOverride,
            modelStateAt = delivery.modelStateAt,
        )
        return if (inserted == 1) delivery else null
    }

    override fun findDue(limit: Int): List<WebhookDelivery> =
        repository.findDue(WebhookDeliveryStatus.PENDING, Instant.now(), PageRequest.of(0, limit)).map { it.toDomain() }

    override fun save(delivery: WebhookDelivery): WebhookDelivery {
        val alreadyPersisted = repository.existsById(delivery.id)
        return repository.save(delivery.toEntity(isNew = !alreadyPersisted)).toDomain()
    }

    override fun existsPendingResend(webhookConfigId: UUID, blockchainEventId: UUID): Boolean =
        repository.existsPendingResend(webhookConfigId, blockchainEventId, WebhookDeliveryStatus.PENDING)

    override fun countPendingResend(): Long = repository.countResendByStatus(WebhookDeliveryStatus.PENDING)

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
        origin = origin,
        enrichmentBlockMode = enrichmentBlockMode,
        enrichmentBlockNumber = enrichmentBlockNumber,
        targetUrlOverride = targetUrlOverride,
        modelStateAt = modelStateAt,
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
        origin = origin,
        enrichmentBlockMode = enrichmentBlockMode,
        enrichmentBlockNumber = enrichmentBlockNumber,
        targetUrlOverride = targetUrlOverride,
        modelStateAt = modelStateAt,
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
        enrichmentResults = enrichmentResults?.let { writeReports(it) },
        enrichmentBlockUsed = enrichmentBlockUsed,
        sentPayload = sentPayload,
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
        enrichmentResults = enrichmentResults?.let { readReports(it) },
        enrichmentBlockUsed = enrichmentBlockUsed,
        sentPayload = sentPayload,
    )

    // `enrichment_results` (JSONB): lista de mapas simples — sem polimorfismo, sem depender de KotlinModule.
    private fun writeReports(reports: List<EnrichmentCallReport>): String =
        objectMapper.writeValueAsString(
            reports.map { r ->
                linkedMapOf(
                    "profileId" to r.profileId.toString(),
                    "targetContractAddress" to r.targetContractAddress,
                    "methodName" to r.methodName,
                    "status" to r.status,
                    "outputs" to r.outputs,
                    "code" to r.code,
                    "message" to r.message,
                    "rpcCode" to r.rpcCode,
                    "rpcData" to r.rpcData,
                    "classification" to r.classification,
                    "blockUsed" to r.blockUsed,
                )
            },
        )

    /**
     * Leitura **tolerante**: campos ausentes/desconhecidos são ignorados, item ilegível é descartado e um
     * JSON que não é lista vira `null` — um histórico gravado por outra versão nunca derruba a listagem.
     */
    private fun readReports(json: String): List<EnrichmentCallReport>? {
        val items = runCatching { objectMapper.readValue(json, object : TypeReference<List<Map<String, Any?>>>() {}) }.getOrNull()
            ?: return null
        return items.mapNotNull { m ->
            val profileId = (m["profileId"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return@mapNotNull null
            EnrichmentCallReport(
                profileId = profileId,
                targetContractAddress = m["targetContractAddress"] as? String ?: "",
                methodName = m["methodName"] as? String ?: "",
                status = m["status"] as? String ?: EnrichmentCallReport.STATUS_ERROR,
                outputs = (m["outputs"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() },
                code = m["code"] as? String,
                message = m["message"] as? String,
                rpcCode = (m["rpcCode"] as? Number)?.toInt(),
                rpcData = m["rpcData"] as? String,
                classification = m["classification"] as? String,
                blockUsed = m["blockUsed"] as? String,
            )
        }
    }
}
