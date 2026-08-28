package br.com.bbchain.evex.indexer.domain.webhook

/**
 * Porta de saída HTTP pura pro disparo de um webhook — implementação Spring `RestClient` em
 * `adapters/outbound/webhook/WebhookHttpClient`. Nunca lança: qualquer exceção de rede/timeout vira
 * [WebhookAttemptResult] com `success=false`/`errorMessage` preenchido (mesmo espírito de resiliência
 * de `BlockscoutClient`, E6).
 */
interface WebhookDispatchPort {
    fun send(url: String, payload: String): WebhookAttemptResult
}

/**
 * Resultado de 1 tentativa de `POST`. [httpStatus] presente quando a chamada chegou a ter resposta
 * HTTP (mesmo 4xx/5xx, nesse caso `success=false`); ausente quando a falha foi antes disso
 * (timeout/conexão recusada), aí [errorMessage] é quem carrega o motivo.
 */
data class WebhookAttemptResult(
    val success: Boolean,
    val httpStatus: Int?,
    val responseBody: String?,
    val errorMessage: String?,
)
