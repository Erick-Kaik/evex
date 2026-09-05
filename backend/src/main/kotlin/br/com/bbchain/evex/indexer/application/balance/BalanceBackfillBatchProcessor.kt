package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Resultado de 1 lote de backfill de saldo — interno ao caso de uso, não é tipo de domínio. */
data class BalanceBackfillBatchResult(
    val processedCount: Int,
    val deltaCount: Int,
)

/**
 * Processa 1 lote do backfill de saldo sob demanda (E14, DEC-066). Bean **separado** de
 * [BalanceBackfillOrchestrator]/[BalanceBackfillRunner] — mesma razão de self-invocation já
 * documentada pro `@Transactional` (`IngestionBatchProcessor` E4, `RedecodeBatchProcessor` E5):
 * `@Transactional` não funciona quando o método é chamado a partir da mesma classe (não passa pelo
 * proxy do Spring).
 *
 * Exactly-once/resumível: os deltas do lote e o avanço do cursor de progresso da config
 * (`backfillCursorBlock`/`backfillCursorLogIndex`) são gravados **na mesma transação** — ou os dois,
 * ou nenhum. Re-disparar o backfill continua de onde parou; ao alcançar a marca d'água a query
 * devolve lote vazio e o backfill vira no-op.
 */
@Component
class BalanceBackfillBatchProcessor(
    private val configs: BalanceMonitorConfigPort,
    private val events: BlockchainEventPort,
    private val balanceProjectionService: BalanceProjectionService,
) {
    @Transactional
    fun processBatch(configId: UUID, batchSize: Int): BalanceBackfillBatchResult {
        val config = configs.findById(configId) ?: return BalanceBackfillBatchResult(processedCount = 0, deltaCount = 0)
        val page = events.findDecodedForBackfill(
            contractAddress = config.contractAddress,
            network = config.network,
            eventName = config.eventName,
            afterBlock = config.backfillCursorBlock,
            afterLogIndex = config.backfillCursorLogIndex,
            maxBlock = config.backfillWatermarkBlock,
            limit = batchSize,
        )
        if (page.isEmpty()) {
            return BalanceBackfillBatchResult(processedCount = 0, deltaCount = 0)
        }
        val deltaCount = balanceProjectionService.projectHistoryForConfig(page, config)
        val last = page.last()
        configs.save(config.copy(backfillCursorBlock = last.blockNumber, backfillCursorLogIndex = last.logIndex))
        return BalanceBackfillBatchResult(processedCount = page.size, deltaCount = deltaCount)
    }
}
