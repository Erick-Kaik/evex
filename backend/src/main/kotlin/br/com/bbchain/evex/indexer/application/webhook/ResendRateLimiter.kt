package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.webhook.RateLimitExceededException
import java.time.Clock
import java.util.ArrayDeque
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Limitador de taxa em memória (janela deslizante de 1 minuto, `Clock` injetável, thread-safe) para
 * preview/reenvio — a PoC não tem autenticação, então o abuso é contido por instância (E33 / DEC-116):
 * - [checkPreview]: conta 1 na janela da INSTÂNCIA (`max-requests-per-minute-per-instance`);
 * - [checkResend]: conta 1 na janela do WEBHOOK (`max-resends-per-minute-per-webhook`) E na da instância.
 * Excedente => [RateLimitExceededException] (`RATE_LIMITED`) com `retryAfterSeconds` = quando a requisição mais
 * antiga da janela estourada sai dela. Estado zera no restart (aceito: 1 instância por rede).
 * As duas janelas só são gravadas se ambas passarem (uma requisição rejeitada não consome cota).
 */
@Component
class ResendRateLimiter(
    private val properties: WebhookResendProperties,
    private val clock: Clock,
) {
    @Autowired
    constructor(properties: WebhookResendProperties) : this(properties, Clock.systemUTC())

    private val lock = Any()
    private val instanceWindow = ArrayDeque<Long>()
    private val webhookWindows = HashMap<UUID, ArrayDeque<Long>>()

    fun checkPreview() = synchronized(lock) {
        val now = clock.millis()
        prune(instanceWindow, now)
        retryAfter(instanceWindow, properties.maxRequestsPerMinutePerInstance, now)?.let { throw rateLimited(it) }
        instanceWindow.addLast(now)
    }

    fun checkResend(webhookId: UUID) = synchronized(lock) {
        val now = clock.millis()
        prune(instanceWindow, now)
        webhookWindows.values.forEach { prune(it, now) }
        webhookWindows.values.removeIf { it.isEmpty() }
        val webhookWindow = webhookWindows[webhookId] ?: ArrayDeque()
        val wait = listOfNotNull(
            retryAfter(webhookWindow, properties.maxResendsPerMinutePerWebhook, now),
            retryAfter(instanceWindow, properties.maxRequestsPerMinutePerInstance, now),
        ).maxOrNull()
        if (wait != null) throw rateLimited(wait)
        webhookWindows.getOrPut(webhookId) { ArrayDeque() }.addLast(now)
        instanceWindow.addLast(now)
    }

    private fun prune(window: ArrayDeque<Long>, now: Long) {
        while (window.isNotEmpty() && now - window.first >= WINDOW_MS) window.removeFirst()
    }

    /** Segundos de espera se [window] já atingiu [limit] (`null` = livre). */
    private fun retryAfter(window: ArrayDeque<Long>, limit: Int, now: Long): Long? {
        if (window.size < limit.coerceAtLeast(1)) return null
        val oldest = window.first
        return maxOf(1L, (oldest + WINDOW_MS - now + 999) / 1000)
    }

    private fun rateLimited(retryAfterSeconds: Long) = RateLimitExceededException(
        RateLimitExceededException.RATE_LIMITED,
        retryAfterSeconds,
        "Limite de requisições excedido; tente novamente em $retryAfterSeconds segundo(s)",
    )

    private companion object {
        const val WINDOW_MS = 60_000L
    }
}
