package br.com.bbchain.evex.indexer.domain.webhook

/** Requisição de preview/reenvio com mais eventos que o teto configurado — 400 `TOO_MANY_EVENTS`, nada é processado. */
class TooManyEventsException(val limit: Int, val requested: Int) :
    RuntimeException("Máximo de $limit evento(s) por requisição; recebido: $requested")

/** Requisição de preview/reenvio semanticamente inválida (ex.: lista de eventos vazia, `BLOCK_AT_DATE` sem data) — 400. */
class InvalidWebhookRequestException(message: String) : RuntimeException(message)

/** Reenvio de webhook inativo (pausado) sem `overrideUrl` válido — 409 `WEBHOOK_INACTIVE`. */
class WebhookInactiveException(val webhookId: java.util.UUID) :
    RuntimeException("Webhook $webhookId está inativo; ative-o ou informe uma overrideUrl válida para reenviar")

/** `overrideUrl` informada com a capacidade desligada (`indexer.webhook.resend.allow-url-override=false`) — 400 `URL_OVERRIDE_DISABLED`. */
class UrlOverrideDisabledException :
    RuntimeException("A URL de destino de teste (overrideUrl) está desabilitada nesta instância")

/** URL de destino rejeitada pela [OutboundUrlPolicy] — 400 `URL_NOT_ALLOWED`. Mensagem GENÉRICA: nunca ecoa URL nem IP resolvido. */
class UrlNotAllowedException :
    RuntimeException("A URL de destino não é permitida pela política de destino desta instância")

/**
 * Limite de abuso excedido — 429 com `Retry-After`. [code] é um código extensível (`RATE_LIMITED`,
 * `RESEND_QUEUE_FULL`); [retryAfterSeconds] vira o cabeçalho (o handler o seta).
 */
class RateLimitExceededException(val code: String, val retryAfterSeconds: Long, message: String) : RuntimeException(message) {
    companion object {
        const val RATE_LIMITED = "RATE_LIMITED"
        const val RESEND_QUEUE_FULL = "RESEND_QUEUE_FULL"
    }
}
