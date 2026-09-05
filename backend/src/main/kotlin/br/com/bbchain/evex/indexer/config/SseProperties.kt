package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.sse.*` (`application.yml`) — parâmetros dos streams SSE de status de job (E17 / DEC-074).
 * Mesmo padrão `@Component` + `@ConfigurationProperties` de [RedecodeProperties]/[BalanceProperties].
 *
 * - [heartbeatIntervalMs]: intervalo entre `event: heartbeat` (default 15 s — abaixo do timeout
 *   ocioso típico de proxy).
 * - [emitterTimeoutMs]: timeout do `SseEmitter`. Longo de propósito (default 30 min): no timeout o
 *   `EventSource` do browser reconecta sozinho; serve só pra não acumular conexões zumbis pra sempre.
 */
@Component
@ConfigurationProperties(prefix = "indexer.sse")
class SseProperties {
    var heartbeatIntervalMs: Long = 15_000
    var emitterTimeoutMs: Long = 1_800_000
}
