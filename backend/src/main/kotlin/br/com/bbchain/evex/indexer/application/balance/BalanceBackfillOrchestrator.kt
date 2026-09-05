package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBackfillStatus
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.jobstatus.JobStatusPublisherPort
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Dono do estado do backfill de saldo sob demanda (E14, DEC-066). Espelha `RedecodeOrchestrator`
 * (E5): `AtomicBoolean` garante **1 backfill por vez em toda a instância** (não por config — o custo
 * é o mesmo e simplifica o controle), [runningConfigId] guarda qual config está rodando agora.
 *
 * Dispara o loop via [BalanceBackfillRunner] — bean **separado**, não um `@Async` desta própria
 * classe (self-invocation não passa pelo proxy do Spring, executaria síncrono e bloquearia a request
 * HTTP de `POST /v1/balance-monitors/{id}/backfill`).
 */
@Service
class BalanceBackfillOrchestrator(
    private val runner: BalanceBackfillRunner,
    private val events: BlockchainEventPort,
    private val configs: BalanceMonitorConfigPort,
    /**
     * Publica cada transição de estado do job global pra quem consome o status ao vivo (E17 /
     * DEC-078: hoje o `BalanceBackfillStatusSseBroker` via SSE). Spring resolve por tipo genérico.
     * `publish` é síncrono, não-transacional e best-effort — não segura o runner.
     */
    private val statusPublisher: JobStatusPublisherPort<BalanceBackfillStatus>,
) {
    private val running = AtomicBoolean(false)
    private val totalProcessed = AtomicLong(0)
    private val totalDeltas = AtomicLong(0)

    /** Config com o backfill em andamento agora — `null` quando ocioso. */
    @Volatile
    private var runningConfigId: UUID? = null

    /**
     * Config da execução mais recente (em andamento ou já concluída) — **não** é zerada em
     * [onFinished], pra [globalStatus] continuar reportando os contadores da última execução
     * ("concluído: processados N") mesmo depois de terminada.
     */
    @Volatile
    private var lastRunConfigId: UUID? = null

    /**
     * `compareAndSet(false, true)` garante 1 execução por vez — se já havia um backfill rodando
     * (desta ou de outra config), retorna `false` sem disparar nada. Se conseguiu, zera os
     * contadores, guarda a config e dispara o [BalanceBackfillRunner] — não bloqueia o chamador.
     */
    fun tryStart(configId: UUID): Boolean {
        if (!running.compareAndSet(false, true)) {
            return false
        }
        totalProcessed.set(0)
        totalDeltas.set(0)
        runningConfigId = configId
        lastRunConfigId = configId
        publishStatusSafely()
        runner.run(configId, this)
        return true
    }

    /**
     * Publica o status global pra quem escuta ao vivo (E17) **sem nunca deixar uma falha abortar o
     * backfill** — cobre o `publish` do broker e a montagem de [globalStatus] (que bate no banco via
     * `findById` + `countDecodedForBackfill`). Falha vira `warn`, não `catch (Exception)` no runner.
     */
    private fun publishStatusSafely() {
        try {
            statusPublisher.publish(globalStatus())
        } catch (ex: Exception) {
            log.warn("Falha ao publicar status SSE de backfill (ignorada, não afeta o job): {}", ex.message)
        }
    }

    /**
     * Status do job **global** (E14, DEC-067): não recebe `configId` porque o backfill é único em
     * toda a instância. [BalanceBackfillStatus.configId] diz qual config está reprojetando agora
     * (`null` quando ocioso) — a UI usa isso pra desabilitar o botão das demais linhas. Os contadores
     * são os da execução mais recente ([lastRunConfigId], não zerados em [onFinished]); `remainingCount`
     * é consultado ao vivo pra a config em foco (a que roda agora, ou a última que rodou).
     */
    fun globalStatus(): BalanceBackfillStatus {
        val focusConfigId = runningConfigId ?: lastRunConfigId
        val remaining = focusConfigId?.let { id ->
            configs.findById(id)?.let { config ->
                events.countDecodedForBackfill(
                    contractAddress = config.contractAddress,
                    network = config.network,
                    eventName = config.eventName,
                    afterBlock = config.backfillCursorBlock,
                    afterLogIndex = config.backfillCursorLogIndex,
                    maxBlock = config.backfillWatermarkBlock,
                )
            }
        } ?: 0L
        return BalanceBackfillStatus(
            running = running.get(),
            configId = runningConfigId,
            processedCount = totalProcessed.get(),
            deltaCount = totalDeltas.get(),
            remainingCount = remaining,
            caughtUp = remaining == 0L,
        )
    }

    /** Chamado por [BalanceBackfillRunner] depois de cada lote — acumula os contadores. */
    fun onBatchProcessed(result: BalanceBackfillBatchResult) {
        totalProcessed.addAndGet(result.processedCount.toLong())
        totalDeltas.addAndGet(result.deltaCount.toLong())
        publishStatusSafely()
    }

    /** Chamado por [BalanceBackfillRunner] em `finally`, sucesso ou falha — libera a próxima execução. */
    fun onFinished() {
        runningConfigId = null
        running.set(false)
        publishStatusSafely()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(BalanceBackfillOrchestrator::class.java)
    }
}
