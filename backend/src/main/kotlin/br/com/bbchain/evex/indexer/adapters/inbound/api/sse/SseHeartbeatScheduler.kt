package br.com.bbchain.evex.indexer.adapters.inbound.api.sse

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Emite `event: heartbeat` em todos os streams SSE abertos a cada `indexer.sse.heartbeat-interval-ms`
 * (E17 / DEC-074). Objetivo: manter a conexão viva contra proxies/load balancers que matam conexões
 * ociosas (~30–60 s típico) — o intervalo default (15 s) fica abaixo disso. O `EventSource` já
 * reconecta sozinho, mas reconexão perde o frame inicial e gera ruído; heartbeat evita.
 *
 * `@Scheduled` (mesmo mecanismo já usado por `BlockchainPoller`/`BalanceSnapshotJob` — Spring não roda
 * 2 execuções do mesmo método em paralelo). Itera os dois brokers. O intervalo vem do bean
 * [SseProperties][br.com.bbchain.evex.indexer.config.SseProperties] via SpEL (`#{@sseProperties...}`)
 * — fonte única, sem duplicar o default aqui.
 */
@Component
class SseHeartbeatScheduler(
    private val redecodeBroker: RedecodeStatusSseBroker,
    private val balanceBackfillBroker: BalanceBackfillStatusSseBroker,
) {

    @Scheduled(fixedDelayString = "#{@sseProperties.heartbeatIntervalMs}")
    fun sendHeartbeats() {
        redecodeBroker.heartbeat()
        balanceBackfillBroker.heartbeat()
    }
}
