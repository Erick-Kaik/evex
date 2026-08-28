package br.com.bbchain.evex.indexer.adapters.outbound.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

private const val MAX_RESPONSE_BODY_LENGTH = 2_000

/**
 * Implementação HTTP de [WebhookDispatchPort] — Spring `RestClient` (decisão travada do projeto,
 * `backend/CLAUDE.md`: "RestClient — para Blockscout e dispatch de webhooks"). `POST` com o [payload]
 * já pronto (renderizado no momento do match, `WebhookMatchingService`) como corpo `application/json`.
 *
 * Timeout curto configurável (`indexer.webhook.request-timeout-ms`) — um endpoint de terceiro lento
 * nunca pode travar o `WebhookDispatchJob` indefinidamente. Qualquer exceção de rede/timeout/status
 * 4xx/5xx vira [WebhookAttemptResult] com `success=false`, nunca propaga exceção crua (mesmo espírito
 * de resiliência de `BlockscoutClient`, E6).
 *
 * Achado de revisão (`reviewer`, E8): uma `url` sintaticamente aceita como `java.net.URI` (tipo do
 * DTO gerado) mas relativa/malformada (ex.: `"not-a-url"`) faz `RestClient.uri(url)` lançar
 * `IllegalArgumentException`/`org.springframework.web.util.InvalidUrlException` — nenhuma delas é
 * `RestClientException`, então escapava dos `catch`es abaixo. `WebhookConfigService.validateUrl()`
 * (E8, fix de revisão) já barra isso no cadastro/edição, mas este `catch` genérico fica como rede de
 * segurança de última linha — coerente com o próprio contrato documentado desta classe ("nunca
 * propaga exceção crua").
 */
@Component
class WebhookHttpClient(
    restClientBuilder: RestClient.Builder,
    private val properties: WebhookProperties,
) : WebhookDispatchPort {

    private val restClient: RestClient by lazy {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(properties.requestTimeoutMs)
            setReadTimeout(properties.requestTimeoutMs)
        }
        restClientBuilder.requestFactory(requestFactory).build()
    }

    override fun send(url: String, payload: String): WebhookAttemptResult =
        try {
            val response = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toEntity(String::class.java)
            WebhookAttemptResult(
                success = response.statusCode.is2xxSuccessful,
                httpStatus = response.statusCode.value(),
                responseBody = response.body?.take(MAX_RESPONSE_BODY_LENGTH),
                errorMessage = null,
            )
        } catch (ex: RestClientResponseException) {
            // Resposta HTTP chegou, mas com status de erro (4xx/5xx) — retrieve() lança por padrão.
            WebhookAttemptResult(
                success = false,
                httpStatus = ex.statusCode.value(),
                responseBody = ex.responseBodyAsString.take(MAX_RESPONSE_BODY_LENGTH),
                errorMessage = null,
            )
        } catch (ex: RestClientException) {
            // Falha antes de qualquer resposta HTTP (timeout, conexão recusada, DNS, etc.).
            WebhookAttemptResult(success = false, httpStatus = null, responseBody = null, errorMessage = ex.message)
        } catch (ex: IllegalArgumentException) {
            // URL sintaticamente aceita no cadastro (java.net.URI relativa) mas inválida pra uma
            // requisição HTTP de verdade (sem host/esquema) - RestClient.uri(...)/SimpleClientHttpRequestFactory
            // lançam IllegalArgumentException (ou o subtipo InvalidUrlException) direto, sem passar
            // por RestClientException. Rede de segurança - o caminho normal é barrado antes, no cadastro.
            WebhookAttemptResult(success = false, httpStatus = null, responseBody = null, errorMessage = ex.message)
        }
}
