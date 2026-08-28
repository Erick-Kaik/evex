package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.blockchain.PollWindow
import br.com.bbchain.evex.indexer.domain.blockchain.RpcRangeException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Poller agendado contra a rede — `eth_getLogs` sem filtro de endereço (DEC-001), cursor global
 * (DEC-002), auto-shrink em erro de range (`ingestion-pipeline-reference.md` §2).
 *
 * `@ConditionalOnProperty` na **classe** (não dá pra condicionar um método individual do jeito que o
 * plano descrevia — condicionais do Spring Boot só se aplicam a definições de bean inteiras) — o bean
 * inteiro só é criado se `indexer.blockchain.polling-enabled` for `true` (default), evitando o
 * `@Scheduled` bater em RPC inexistente durante `@SpringBootTest` (setado via
 * `@DynamicPropertySource` em `AbstractIntegrationTest`, nunca em `src/test/resources/application.yml`
 * — ver DEC-024, esse arquivo shadowa `src/main/resources/application.yml` no classpath de teste).
 */
@Component
@ConditionalOnProperty(
    prefix = "indexer.blockchain",
    name = ["polling-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class BlockchainPoller(
    private val blockchain: BlockchainPort,
    private val cursor: IngestionCursorPort,
    private val batchProcessor: IngestionBatchProcessor,
    private val properties: BlockchainProperties,
) {
    private val logger = LoggerFactory.getLogger(BlockchainPoller::class.java)

    @Scheduled(fixedDelayString = "\${indexer.blockchain.poll-interval-ms}")
    fun poll() {
        try {
            val latest = blockchain.currentBlockNumber()
            val lastProcessed = cursor.current()
            val window = PollWindow.next(lastProcessed, latest, properties.confirmations, properties.batchSize)
                ?: return // em dia, nada confirmado pendente
            processRange(window.first, window.last)
        } catch (ex: Exception) {
            // Qualquer exceção (inclusive RpcRangeException que não coube encolher mais, ou erro de
            // transporte) é capturada e logada aqui — nunca derruba o @Scheduled.
            logger.error("Falha no poll do indexador: {}", ex.message, ex)
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
