package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.config.BalanceProperties
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Loop assíncrono do backfill de saldo sob demanda (E14, DEC-066) — bean **separado** de
 * [BalanceBackfillOrchestrator] (`@Async` só funciona via proxy do Spring, não por self-invocation —
 * mesma razão de `RedecodeRunner` na E5).
 *
 * Para quando o lote vem vazio (`processedCount == 0`) — diferente do redecode, aqui não existe
 * "evento permanentemente não processável": todo evento devolvido pela query avança o cursor de
 * progresso e não reaparece no lote seguinte, então "lote vazio" é o critério de parada correto e
 * sempre alcançável (ao chegar na marca d'água).
 */
@Component
class BalanceBackfillRunner(
    private val batchProcessor: BalanceBackfillBatchProcessor,
    private val properties: BalanceProperties,
) {
    private val logger = LoggerFactory.getLogger(BalanceBackfillRunner::class.java)

    @Async
    fun run(configId: UUID, orchestrator: BalanceBackfillOrchestrator) {
        try {
            while (true) {
                val result = batchProcessor.processBatch(configId, properties.backfillBatchSize)
                orchestrator.onBatchProcessed(result)
                if (result.processedCount == 0) {
                    break
                }
            }
        } catch (ex: Exception) {
            // Capturada e logada — o `finally` garante `onFinished()` mesmo em erro, a flag `running`
            // nunca fica travada em `true`.
            logger.error("Falha durante o backfill de saldo (configId={}): {}", configId, ex.message, ex)
        } finally {
            orchestrator.onFinished()
        }
    }
}
