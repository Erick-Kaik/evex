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
import br.com.bbchain.evex.indexer.application.webhook.WebhookConfigService
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.webhook.FilterOperator
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
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
) : WebhooksApiService {

    override fun createWebhook(createWebhookPayload: CreateWebhookPayload): WebhookConfigDto {
        val config = webhookConfigService.register(
            contractAddress = createWebhookPayload.contractAddress,
            network = createWebhookPayload.network.toDomain(),
            eventName = createWebhookPayload.eventName,
            url = createWebhookPayload.url.toString(),
            filters = createWebhookPayload.filters.orEmpty().map { it.toDomain() },
            payloadTemplate = createWebhookPayload.payloadTemplate,
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
    )
}
