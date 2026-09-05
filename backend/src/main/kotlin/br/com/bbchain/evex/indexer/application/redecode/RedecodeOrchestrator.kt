package br.com.bbchain.evex.indexer.application.redecode

import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.jobstatus.JobStatusPublisherPort
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Dono do estado da reindexação assíncrona (`ingestion-pipeline-reference.md` §4). `AtomicBoolean`
 * garante 1 execução por vez (`tryStart`). Delegado direto por `RedecodeApiServiceImpl` — é o próprio
 * caso de uso, não precisa de um serviço de aplicação intermediário adicional.
 *
 * Dispara o loop via [RedecodeRunner] — bean **separado**, não um método `@Async` desta própria classe:
 * `@Async` sofre do mesmo problema de self-invocation já resolvido para `@Transactional` em
 * [RedecodeBatchProcessor]/`IngestionBatchProcessor` (E4) — `this.run(...)` a partir da mesma classe
 * não passa pelo proxy do Spring e executaria síncrono, bloqueando quem chamou [tryStart] (a request
 * HTTP de `POST /v1/redecode`, ou `AbiRegistrationService.register()`).
 */
@Service
class RedecodeOrchestrator(
    private val runner: RedecodeRunner,
    private val events: BlockchainEventPort,
    /**
     * Publica cada transição de estado pra quem consome o status ao vivo (E17 / DEC-078: hoje o
     * `RedecodeStatusSseBroker` via SSE). Spring resolve por tipo genérico. `publish` é síncrono,
     * não-transacional e best-effort (só empurra pro registro de emitters) — não segura o runner.
     */
    private val statusPublisher: JobStatusPublisherPort<RedecodeStatus>,
) {
    private val running = AtomicBoolean(false)
    private val totalProcessed = AtomicLong(0)
    private val totalDecoded = AtomicLong(0)

    @Volatile
    private var lastFilter: RedecodeFilter = RedecodeFilter()

    /**
     * `compareAndSet(false, true)` garante 1 execução por vez — se já havia uma rodando, retorna
     * `false` sem disparar nada nova (não enfileira, não acumula). Se conseguiu, zera os contadores,
     * guarda [filter] (usado por [status] pra consultar `remainingCount` do filtro certo) e dispara o
     * [RedecodeRunner] — **não bloqueia o chamador**, o `@Async` retorna na hora.
     */
    fun tryStart(filter: RedecodeFilter): Boolean {
        if (!running.compareAndSet(false, true)) {
            return false
        }
        totalProcessed.set(0)
        totalDecoded.set(0)
        lastFilter = filter
        publishStatusSafely()
        runner.run(filter, this)
        return true
    }

    /**
     * Publica o status pra quem escuta ao vivo (E17) **sem nunca deixar uma falha abortar o job** —
     * a proteção cobre tanto o `publish` do broker quanto a montagem do snapshot [status] (que hoje
     * bate no banco via `countPending`). Um hiccup transitório de banco aqui vira só um `warn`, não
     * um `catch (Exception)` no runner que encerraria a reindexação no meio.
     */
    private fun publishStatusSafely() {
        try {
            statusPublisher.publish(status())
        } catch (ex: Exception) {
            log.warn("Falha ao publicar status SSE de redecode (ignorada, não afeta o job): {}", ex.message)
        }
    }

    fun status(): RedecodeStatus = RedecodeStatus(
        running = running.get(),
        processedCount = totalProcessed.get(),
        decodedCount = totalDecoded.get(),
        remainingCount = events.countPending(lastFilter),
    )

    /** Chamado por [RedecodeRunner] depois de cada lote — acumula os contadores. */
    fun onBatchProcessed(result: RedecodeBatchResult) {
        totalProcessed.addAndGet(result.processedCount.toLong())
        totalDecoded.addAndGet(result.decodedCount.toLong())
        publishStatusSafely()
    }

    /** Chamado por [RedecodeRunner] em `finally`, sucesso ou falha — libera a próxima execução. */
    fun onFinished() {
        running.set(false)
        publishStatusSafely()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RedecodeOrchestrator::class.java)
    }
}
