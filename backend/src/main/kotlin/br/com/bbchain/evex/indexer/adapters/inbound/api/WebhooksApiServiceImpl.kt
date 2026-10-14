package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.WebhooksApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.CreateWebhookPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.FilterCondition as FilterConditionDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.FilterOperator as FilterOperatorDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateWebhookPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateWebhookStatusPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookConfig as WebhookConfigDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookConfigPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookDelivery as WebhookDeliveryDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookDeliveryAttempt as WebhookDeliveryAttemptDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookDeliveryPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookDeliveryStatus as WebhookDeliveryStatusDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookMatchingEventPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookPreviewPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookPreviewResult
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookResendPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.WebhookResendResult
import br.com.bbchain.evex.indexer.application.webhook.WebhookConfigService
import br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingEventsService
import br.com.bbchain.evex.indexer.application.webhook.WebhookPreviewService
import br.com.bbchain.evex.indexer.application.webhook.WebhookResendService
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.webhook.FilterOperator
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
import br.com.bbchain.evex.indexer.domain.webhook.redactUrl
import java.time.ZoneOffset
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Implementação fina de [WebhooksApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [WebhookConfigService] (config)/[WebhookDeliveryPort] (leitura pura de entregas, mesmo padrão de
 * `BalancesApiServiceImpl` não precisando de serviço de aplicação intermediário). Nenhuma regra de
 * negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class WebhooksApiServiceImpl(
    private val webhookConfigService: WebhookConfigService,
    private val deliveries: WebhookDeliveryPort,
    private val previewService: WebhookPreviewService,
    private val matchingEventsService: WebhookMatchingEventsService,
    private val resendService: WebhookResendService,
) : WebhooksApiService {

    override fun createWebhook(createWebhookPayload: CreateWebhookPayload): WebhookConfigDto {
        val config = webhookConfigService.register(
            contractAddress = createWebhookPayload.contractAddress,
            network = createWebhookPayload.network.toDomain(),
            eventName = createWebhookPayload.eventName,
            url = createWebhookPayload.url.toString(),
            filters = createWebhookPayload.filters.orEmpty().map { it.toDomain() },
            payloadTemplate = createWebhookPayload.payloadTemplate,
            enrichmentProfileIds = createWebhookPayload.enrichmentProfileIds.orEmpty(),
            entityModelIds = createWebhookPayload.entityModelIds.orEmpty(),
        )
        return config.toDto()
    }

    override fun getWebhook(id: UUID): WebhookConfigDto = webhookConfigService.get(id).toDto()

    override fun listWebhooks(page: kotlin.Int, size: kotlin.Int): WebhookConfigPage {
        val result = webhookConfigService.list(page, size)
        return WebhookConfigPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    override fun updateWebhook(id: UUID, updateWebhookPayload: UpdateWebhookPayload): WebhookConfigDto {
        val config = webhookConfigService.update(
            id = id,
            contractAddress = updateWebhookPayload.contractAddress,
            network = updateWebhookPayload.network.toDomain(),
            eventName = updateWebhookPayload.eventName,
            url = updateWebhookPayload.url.toString(),
            filters = updateWebhookPayload.filters.orEmpty().map { it.toDomain() },
            payloadTemplate = updateWebhookPayload.payloadTemplate,
            enrichmentProfileIds = updateWebhookPayload.enrichmentProfileIds.orEmpty(),
            entityModelIds = updateWebhookPayload.entityModelIds.orEmpty(),
        )
        return config.toDto()
    }

    override fun updateWebhookStatus(id: UUID, updateWebhookStatusPayload: UpdateWebhookStatusPayload): WebhookConfigDto =
        webhookConfigService.updateStatus(id, updateWebhookStatusPayload.active).toDto()

    override fun listWebhookDeliveries(id: UUID, page: kotlin.Int, size: kotlin.Int): WebhookDeliveryPage {
        // Efeito colateral: 404 se o webhook não existir — mesmo padrão de
        // ContractsApiServiceImpl.suggestInterface (E6), que também usa outra porta só pelo 404.
        webhookConfigService.get(id)
        val result = deliveries.search(webhookConfigId = id, status = null, page = page, size = size)
        return WebhookDeliveryPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    // ---- E33 ----

    override fun listWebhookMatchingEvents(
        id: UUID,
        fromBlock: kotlin.Long?,
        toBlock: kotlin.Long?,
        fromDate: java.time.OffsetDateTime?,
        toDate: java.time.OffsetDateTime?,
        cursor: kotlin.String?,
        size: kotlin.Int,
    ): WebhookMatchingEventPage = matchingEventsService.listMatchingEvents(
        webhookId = id,
        fromBlock = fromBlock,
        toBlock = toBlock,
        fromDate = fromDate?.toInstant(),
        toDate = toDate?.toInstant(),
        cursor = cursor,
        size = size,
    ).toDto()

    override fun previewWebhook(id: UUID, webhookPreviewPayload: WebhookPreviewPayload): WebhookPreviewResult =
        previewService.preview(
            webhookId = id,
            eventIds = webhookPreviewPayload.eventIds,
            enrichmentBlock = webhookPreviewPayload.enrichmentBlock?.toDomain() ?: EnrichmentBlockSpec.EventBlock,
            diagnose = webhookPreviewPayload.diagnose ?: false,
        ).toDto()

    // 202 declarado no contrato (webhook-resend.yaml): as entregas são criadas PENDING e processadas pelo job.
    override fun resendWebhook(id: UUID, webhookResendPayload: WebhookResendPayload): WebhookResendResult =
        resendService.resend(
            webhookId = id,
            eventIds = webhookResendPayload.eventIds,
            range = webhookResendPayload.range?.toDomain(),
            enrichmentBlock = webhookResendPayload.enrichmentBlock?.toDomain() ?: EnrichmentBlockSpec.EventBlock,
            overrideUrl = webhookResendPayload.overrideUrl?.toString(),
        ).toDto()

    private fun NetworkDto.toDomain(): Network = Network.valueOf(this.name)

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    private fun FilterOperatorDto.toDomain(): FilterOperator = FilterOperator.valueOf(this.name)

    private fun FilterOperator.toDto(): FilterOperatorDto = FilterOperatorDto.valueOf(this.name)

    private fun WebhookDeliveryStatus.toDto(): WebhookDeliveryStatusDto = WebhookDeliveryStatusDto.valueOf(this.name)

    private fun FilterConditionDto.toDomain(): WebhookFilterCondition = WebhookFilterCondition(
        paramName = paramName,
        operator = operator.toDomain(),
        value = value,
    )

    private fun WebhookFilterCondition.toDto(): FilterConditionDto = FilterConditionDto(
        paramName = paramName,
        operator = operator.toDto(),
        value = value,
    )

    private fun WebhookConfig.toDto(): WebhookConfigDto = WebhookConfigDto(
        id = id,
        contractAddress = contractAddress,
        network = network.toDto(),
        eventName = eventName,
        url = java.net.URI.create(url),
        filters = filters.map { it.toDto() }.toMutableList(),
        payloadTemplate = payloadTemplate,
        active = active,
        createdAt = createdAt.atOffset(ZoneOffset.UTC),
        enrichmentProfileIds = enrichmentProfileIds.toMutableList(),
        entityModelIds = entityModelIds.toMutableList(),
    )

    private fun WebhookDeliveryAttempt.toDto(): WebhookDeliveryAttemptDto = WebhookDeliveryAttemptDto(
        id = id,
        deliveryId = deliveryId,
        attemptNumber = attemptNumber,
        attemptedAt = attemptedAt.atOffset(ZoneOffset.UTC),
        success = success,
        httpStatus = httpStatus,
        responseBody = responseBody,
        errorMessage = errorMessage,
        enrichmentResults = enrichmentResults?.map { it.toDto() }?.toMutableList(),
        enrichmentBlockUsed = enrichmentBlockUsed,
        sentPayload = sentPayload,
    )

    private fun WebhookDelivery.toDto(): WebhookDeliveryDto = WebhookDeliveryDto(
        id = id,
        webhookConfigId = webhookConfigId,
        blockchainEventId = blockchainEventId,
        payload = payload,
        status = status.toDto(),
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt?.atOffset(ZoneOffset.UTC),
        createdAt = createdAt.atOffset(ZoneOffset.UTC),
        attempts = deliveries.findAttempts(id).map { it.toDto() }.toMutableList(),
        origin = origin,
        resend = resend,
        enrichmentBlockMode = enrichmentBlockMode,
        enrichmentBlockNumber = enrichmentBlockNumber,
        modelStateAt = modelStateAt?.atOffset(ZoneOffset.UTC),
        targetUrlOverride = redactUrl(targetUrlOverride),
    )
}
