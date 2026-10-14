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
    /**
     * Classificação GENÉRICA da falha de transporte (`TIMEOUT`, `CONNECT_FAILED`, `UNREACHABLE`, `INVALID_URL`) —
     * string extensível, sem detalhe de rede. Usada nas entregas com URL de teste, onde a mensagem crua viraria
     * um oráculo de port scan. `null` quando houve resposta HTTP ou sucesso.
     */
    val errorCode: String? = null,
)
