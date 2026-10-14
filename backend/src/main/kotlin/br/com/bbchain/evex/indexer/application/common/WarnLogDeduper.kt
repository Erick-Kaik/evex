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
    // Estado por chave (UUID ou String — mesmo mapa; os dois tipos nunca colidem): quando foi o último log
    // emitido e quantas ocorrências foram suprimidas desde então (para o log agregado "repetida N vezes").
    private data class State(val lastLoggedAt: Instant, val suppressed: Int)

    private val states = ConcurrentHashMap<Any, State>()

    /**
     * `true` na 1ª ocorrência de [key], ou quando [interval] já passou desde o último log
     * efetivamente emitido pra essa chave. O carimbo de tempo só é atualizado quando a resposta é
     * `true` (não a cada chamada) — senão um fluxo contínuo de eventos batendo na mesma chave a cada
     * poucos milissegundos nunca deixaria o intervalo "vencer" de verdade (cada chamada reiniciaria a
     * contagem a partir de si mesma, mesmo sem nunca ter logado).
     */
    fun shouldLog(key: UUID): Boolean = acquire(key) != null

    /** Mesma regra de [shouldLog] para chave textual (ex.: `contrato|método|código`) — chamadores por UUID não mudam. */
    fun shouldLog(key: String): Boolean = acquire(key) != null

    /**
     * Para WARN **agregado por janela**: devolve `null` se o log desta chave está suprimido (dentro de
     * [interval]); senão devolve quantas ocorrências a linha de log representa (1 + as suprimidas desde
     * o último log) — o chamador escreve "repetida N vezes" em vez de N linhas.
     */
    fun occurrencesIfDue(key: String): Int? = acquire(key)

    private fun acquire(key: Any): Int? {
        val current = now()
        var occurrences: Int? = null
        states.compute(key) { _, previous ->
            if (previous == null || Duration.between(previous.lastLoggedAt, current) >= interval) {
                occurrences = (previous?.suppressed ?: 0) + 1
                State(current, 0)
            } else {
                previous.copy(suppressed = previous.suppressed + 1)
            }
        }
        return occurrences
    }
}
