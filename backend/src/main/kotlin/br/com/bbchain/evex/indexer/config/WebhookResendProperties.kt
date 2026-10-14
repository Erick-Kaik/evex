package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.webhook.resend.*` (E33) — limites e capacidades do preview/reenvio de webhooks. Expostos à UI
 * por `GET /v1/capabilities`. Mesmo padrão `@Component`+`@ConfigurationProperties` de [WebhookProperties].
 */
@Component
@ConfigurationProperties(prefix = "indexer.webhook.resend")
class WebhookResendProperties {
    /**
     * `overrideUrl` no reenvio (URL de destino de teste). DESLIGADO por padrão: sem autenticação na PoC,
     * um POST a URL arbitrária seria um proxy SSRF aberto.
     */
    var allowUrlOverride: Boolean = false

    /** Máximo de eventos por requisição de reenvio (`eventIds` ou `range`). */
    var maxEventsPerRequest: Int = 50

    /** Máximo de eventos por requisição de preview. */
    var previewMaxEvents: Int = 5

    /**
     * `GET /v1/webhooks/{id}/matching-events`: máximo de eventos VARRIDOS por requisição (os filtros do
     * webhook são avaliados em memória); ao atingir, a resposta sai com `scanTruncated = true`.
     */
    var maxScan: Int = 5000

    /**
     * Teto de reenvios `PENDING` (`origin != MATCH`) na fila. Excedente = 429 `RESEND_QUEUE_FULL` com
     * `Retry-After` ([queueFullRetryAfterSeconds]).
     */
    var maxPendingResends: Int = 500

    /** `Retry-After` (segundos) devolvido em `RESEND_QUEUE_FULL`. */
    var queueFullRetryAfterSeconds: Long = 30

    /** Máx. de reenvios por minuto POR WEBHOOK (janela deslizante em memória, por instância). Excedente = 429 `RATE_LIMITED`. */
    var maxResendsPerMinutePerWebhook: Int = 10

    /** Máx. de requisições de preview + reenvio por minuto na INSTÂNCIA inteira. Excedente = 429 `RATE_LIMITED`. */
    var maxRequestsPerMinutePerInstance: Int = 30

    /** Portas de destino permitidas para a URL de teste (a porta implícita do esquema conta como 80/443). */
    var allowedPorts: List<Int> = listOf(80, 443)

    /**
     * Hosts (match exato) e CIDRs liberados mesmo quando resolvem para faixa bloqueada — só para laboratório
     * (ex.: um sink local). Vazia por padrão.
     */
    var urlAllowlist: List<String> = emptyList()
}
