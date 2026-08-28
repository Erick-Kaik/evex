package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.adapters.outbound.webhook.WebhookPayloadRenderer
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
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
) {
    fun register(
        contractAddress: String,
        network: Network,
        eventName: String,
        url: String,
        filters: List<WebhookFilterCondition>,
        payloadTemplate: String?,
    ): WebhookConfig {
        validatePayloadTemplate(payloadTemplate)
        validateUrl(url)
        val config = WebhookConfig(
            id = UUID.randomUUID(),
            contractAddress = contractAddress.lowercase(),
            network = network,
            eventName = eventName,
            url = url,
            filters = filters,
            payloadTemplate = payloadTemplate,
            active = true,
            createdAt = Instant.now(),
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
    ): WebhookConfig {
        validatePayloadTemplate(payloadTemplate)
        validateUrl(url)
        val existing = get(id)
        val updated = existing.copy(
            contractAddress = contractAddress.lowercase(),
            network = network,
            eventName = eventName,
            url = url,
            filters = filters,
            payloadTemplate = payloadTemplate,
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
}
