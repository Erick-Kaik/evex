package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.blockchain.PollWindow
import br.com.bbchain.evex.indexer.domain.blockchain.RpcRangeException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * O "tick" de ingestão em si — `currentBlockNumber()`/`cursor.current()`/[PollWindow.next]/`getLogs`
 * com auto-shrink recursivo/[IngestionBatchProcessor.ingestRange] — extraído do antigo
 * `BlockchainPoller` (E4) na E26/DEC-091, quando o Evex ganhou um 2º jeito de disparar a mesma lógica
 * (`eth_subscribe("newHeads")`, [WebSocketPollTrigger]). Este serviço **não sabe quem chama** — nem
 * `@Scheduled`, nem `@ConditionalOnProperty` aqui; isso é responsabilidade exclusiva dos gatilhos
 * ([ScheduledPollTrigger]/[WebSocketPollTrigger]), que decidem *quando* chamar [tick]. Mesmo
 * try/catch amplo de antes: uma falha de RPC/transporte nunca sobe pra quem chamou [tick] — cada
 * gatilho continua funcionando (o próximo tick do `@Scheduled`, a próxima notificação do WebSocket)
 * mesmo depois de um erro.
 */
@Component
class PollTickService(
    private val blockchain: BlockchainPort,
    private val cursor: IngestionCursorPort,
    private val batchProcessor: IngestionBatchProcessor,
    private val properties: BlockchainProperties,
) {
    private val logger = LoggerFactory.getLogger(PollTickService::class.java)

    fun tick() {
        try {
            val latest = blockchain.currentBlockNumber()
            val lastProcessed = cursor.current()
            val window = PollWindow.next(lastProcessed, latest, properties.confirmations, properties.batchSize)
                ?: return // em dia, nada confirmado pendente
            processRange(window.first, window.last)
        } catch (ex: Exception) {
            // Qualquer exceção (inclusive RpcRangeException que não coube encolher mais, ou erro de
            // transporte) é capturada e logada aqui — nunca derruba quem chamou tick() (nem o
            // @Scheduled do ScheduledPollTrigger, nem a assinatura RxJava do WebSocketPollTrigger).
            logger.error("Falha no tick de ingestão: {}", ex.message, ex)
        }
    }

    /**
     * Não-transacional de propósito — só controla o auto-shrink. A ingestão real (transacional) é
     * delegada ao [IngestionBatchProcessor] (bean separado, mesma razão de self-invocation do padrão
     * de reindexação já documentado em `ingestion-pipeline-reference.md`).
     */
    private fun processRange(from: Long, to: Long) {
        val logs = try {
            blockchain.getLogs(from, to)
        } catch (ex: RpcRangeException) {
            val windowSize = to - from + 1
            if (from >= to || windowSize <= properties.minBatchSize) {
                throw ex // não dá pra encolher mais, sobe o erro
            }
            val mid = from + (to - from) / 2
            processRange(from, mid)
            processRange(mid + 1, to)
            return
        }
        batchProcessor.ingestRange(from, to, logs)
    }
}
