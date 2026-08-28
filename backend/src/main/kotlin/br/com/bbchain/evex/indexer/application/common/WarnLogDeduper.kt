package br.com.bbchain.evex.indexer.application.common

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate-limita/deduplica logs `WARN` repetitivos por chave (tipicamente o `id` de uma
 * `BalanceMonitorConfig`/`WebhookConfig` persistentemente mal configurada, ex.: typo num nome de
 * parâmetro nunca corrigido) — sem isso, uma única config quebrada gera 1 linha de log a cada evento
 * que bate nela, virando ruído recorrente numa rede com tráfego real (pendência aceita em DEC-035,
 * fechada na E11).
 *
 * `ConcurrentHashMap<UUID, Instant>` em memória, uma instância por classe afetada
 * ([br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService],
 * [br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService]) — mesmo espírito de
 * simplicidade das outras configs `indexer.*` já usadas no projeto: sem cache distribuído, sem infra
 * nova.
 *
 * [now] é injetável só para viabilizar teste determinístico ([WarnLogDeduperTest]) — em produção
 * sempre usa o relógio real (`Instant::now`, default do parâmetro).
 */
class WarnLogDeduper(
    private val interval: Duration,
    private val now: () -> Instant = Instant::now,
) {
    private val lastLoggedAt = ConcurrentHashMap<UUID, Instant>()

    /**
     * `true` na 1ª ocorrência de [key], ou quando [interval] já passou desde o último log
     * efetivamente emitido pra essa chave. O carimbo de tempo só é atualizado quando a resposta é
     * `true` (não a cada chamada) — senão um fluxo contínuo de eventos batendo na mesma chave a cada
     * poucos milissegundos nunca deixaria o intervalo "vencer" de verdade (cada chamada reiniciaria a
     * contagem a partir de si mesma, mesmo sem nunca ter logado).
     */
    fun shouldLog(key: UUID): Boolean {
        val current = now()
        var due = false
        lastLoggedAt.compute(key) { _, previous ->
            if (previous == null || Duration.between(previous, current) >= interval) {
                due = true
                current
            } else {
                previous
            }
        }
        return due
    }
}
