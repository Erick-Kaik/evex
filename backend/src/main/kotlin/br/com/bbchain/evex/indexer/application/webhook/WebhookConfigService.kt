package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.adapters.outbound.webhook.WebhookPayloadRenderer
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.webhook.InvalidWebhookConfigException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/** Caso de uso: cadastro, consulta e atualização de configurações de webhook (doc §4.6). */
@Service
class WebhookConfigService(
    private val webhooks: WebhookConfigPort,
    private val payloadRenderer: WebhookPayloadRenderer,
    private val enrichmentProfiles: EnrichmentProfilePort,
    private val entityModels: EntityModelPort,
) {
    fun register(
        contractAddress: String,
        network: Network,
        eventName: String,
        url: String,
        filters: List<WebhookFilterCondition>,
        payloadTemplate: String?,
        enrichmentProfileIds: List<UUID> = emptyList(),
        entityModelIds: List<UUID> = emptyList(),
    ): WebhookConfig {
        val normalizedAddress = contractAddress.lowercase()
        validatePayloadTemplate(payloadTemplate)
        validateUrl(url)
        validateEnrichmentProfileIds(enrichmentProfileIds)
        validateEntityModelIds(entityModelIds, normalizedAddress, eventName)
        val config = WebhookConfig(
            id = UUID.randomUUID(),
            contractAddress = normalizedAddress,
            network = network,
            eventName = eventName,
            url = url,
            filters = filters,
            payloadTemplate = payloadTemplate,
            active = true,
            createdAt = Instant.now(),
            enrichmentProfileIds = enrichmentProfileIds,
            entityModelIds = entityModelIds,
        )
        return webhooks.save(config)
    }

    fun get(id: UUID): WebhookConfig = webhooks.findById(id) ?: throw WebhookConfigNotFoundException(id)

    fun list(page: Int, size: Int): PageResult<WebhookConfig> = webhooks.findAll(page, size)

    fun update(
        id: UUID,
        contractAddress: String,
        network: Network,
        eventName: String,
        url: String,
        filters: List<WebhookFilterCondition>,
        payloadTemplate: String?,
        enrichmentProfileIds: List<UUID> = emptyList(),
        entityModelIds: List<UUID> = emptyList(),
    ): WebhookConfig {
        val normalizedAddress = contractAddress.lowercase()
        validatePayloadTemplate(payloadTemplate)
        validateUrl(url)
        validateEnrichmentProfileIds(enrichmentProfileIds)
        validateEntityModelIds(entityModelIds, normalizedAddress, eventName)
        val existing = get(id)
        val updated = existing.copy(
            contractAddress = normalizedAddress,
            network = network,
            eventName = eventName,
            url = url,
            filters = filters,
            payloadTemplate = payloadTemplate,
            enrichmentProfileIds = enrichmentProfileIds,
            entityModelIds = entityModelIds,
        )
        return webhooks.save(updated)
    }

    fun updateStatus(id: UUID, active: Boolean): WebhookConfig {
        val existing = get(id)
        return webhooks.save(existing.copy(active = active))
    }

    /**
     * Renderiza [payloadTemplate] com valores-dummy e tenta parsear como JSON — pego na hora do
     * cadastro/edição, não silenciosamente no primeiro disparo real (`WebhookPayloadRenderer.
     * validateTemplate`). `null` (sem template, usa o envelope padrão) é sempre válido.
     */
    private fun validatePayloadTemplate(payloadTemplate: String?) {
        if (payloadTemplate != null && !payloadRenderer.validateTemplate(payloadTemplate)) {
            throw InvalidWebhookConfigException("payloadTemplate inválido: não produz JSON válido depois de renderizado.")
        }
    }

    /**
     * Falha rápido no cadastro/edição para `url`s que nunca conseguiriam ser disparadas em runtime —
     * achado de revisão (`reviewer`, E8): `java.net.URI` (tipo do DTO gerado, `format: uri` no spec)
     * aceita URIs **relativas** sem lançar (ex.: `"not-a-url"` é uma URI relativa válida) e o OpenAPI
     * generator não produz nenhuma constraint Bean Validation adicional pra `format: uri` — sem esta
     * checagem, um `url` assim só falhava dentro de [br.com.bbchain.evex.indexer.adapters.outbound.
     * webhook.WebhookHttpClient.send] (`IllegalArgumentException`/`InvalidUrlException` do Spring,
     * levantadas de dentro de `RestClient.uri(...)`, fora do alcance do `catch` de
     * `RestClientException` daquela classe) — exceção crua que escapava pra
     * [WebhookDispatchJob.dispatch], abortando o lote inteiro de entregas devidas a cada execução do
     * `@Scheduled`, permanentemente, não só a config quebrada (ver `docs/decisions.md`).
     */
    private fun validateUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (ex: URISyntaxException) {
            throw InvalidWebhookConfigException("url inválida: $url")
        }
        if (!uri.isAbsolute || uri.scheme?.lowercase() !in setOf("http", "https")) {
            throw InvalidWebhookConfigException("url precisa ser absoluta com esquema http/https: $url")
        }
    }

    /**
     * Falha cedo (E19/DEC-082): cada `id` de [enrichmentProfileIds] precisa existir — sem isso, um id
     * inválido só apareceria silenciosamente no 1º disparo real ([WebhookEnrichmentResolver] resolveria
     * uma lista vazia pra ele, os tokens `{{enriched.*}}` daquele perfil nunca sendo preenchidos).
     */
    private fun validateEnrichmentProfileIds(ids: List<UUID>) {
        if (ids.isEmpty()) return
        val found = enrichmentProfiles.findAllByIds(ids).map { it.id }.toSet()
        val missing = ids.toSet() - found
        if (missing.isNotEmpty()) {
            throw InvalidWebhookConfigException("enrichmentProfileIds inexistentes: $missing")
        }
    }

    /**
     * Falha cedo (E19/DEC-082): cada `id` de [entityModelIds] precisa existir **e** ter algum
     * `ModelContributor` cujo `(contractAddress, eventName)` bata com o `(contractAddress, eventName)`
     * deste próprio webhook — "o webhook só pode ler um modelo do qual o próprio evento dele também
     * participa" (regra do roadmap E19). Sem esta checagem, `{{model.*}}` daquele modelo nunca teria
     * como resolver em runtime (o casamento de contribuidor em
     * [WebhookMatchingService.resolveModelPlaceholders] é sempre contra o evento que disparou o
     * próprio webhook).
     */
    private fun validateEntityModelIds(ids: List<UUID>, contractAddress: String, eventName: String) {
        if (ids.isEmpty()) return
        val models: List<EntityModel> = entityModels.findAllByIds(ids)
        val found = models.map { it.id }.toSet()
        val missing = ids.toSet() - found
        if (missing.isNotEmpty()) {
            throw InvalidWebhookConfigException("entityModelIds inexistentes: $missing")
        }
        models.forEach { model ->
            val hasMatchingContributor = model.contributors.any {
                it.contractAddress.equals(contractAddress, ignoreCase = true) && it.eventName == eventName
            }
            if (!hasMatchingContributor) {
                throw InvalidWebhookConfigException(
                    "entityModel ${model.id} não tem nenhum contribuidor casando com (contractAddress=$contractAddress, eventName=$eventName) deste webhook",
                )
            }
        }
    }
}
