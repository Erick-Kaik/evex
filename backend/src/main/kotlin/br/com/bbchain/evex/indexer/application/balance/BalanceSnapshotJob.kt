package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import java.time.LocalDate
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Job noturno de consolidação de saldo (doc §4.4) — 1 linha por `snapshot_date` em
 * `asset_balance_snapshots`, até o bloco que o poller já processou (`IngestionCursorPort.current()`,
 * E4): o snapshot só é "completo" até ali, não até "agora".
 *
 * Sem guarda de concorrência própria — mesma garantia natural do `@Scheduled` já estabelecida em
 * `BlockchainPoller` (E4): o Spring não roda 2 execuções do mesmo método `@Scheduled` em paralelo.
 */
@Component
class BalanceSnapshotJob(
    private val cursor: IngestionCursorPort,
    private val balances: AssetBalancePort,
) {
    private val logger = LoggerFactory.getLogger(BalanceSnapshotJob::class.java)

    @Scheduled(cron = "\${indexer.balance.snapshot-cron}")
    fun run() {
        val blockNumber = cursor.current()
        if (blockNumber < 0) {
            logger.debug("Snapshot de saldo pulado: nenhum bloco ingerido ainda (cursor = -1).")
            return
        }
        val rows = balances.snapshotAll(LocalDate.now(), blockNumber)
        logger.info("Snapshot de saldo gerado: {} linhas (bloco {}, data {}).", rows, blockNumber, LocalDate.now())
    }
}
