package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService
import br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiDecodingPort
import br.com.bbchain.evex.indexer.domain.abi.RawLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Ingestão transacional de uma janela de blocos já buscada com sucesso (`ingestion-pipeline-reference.md`
 * §2/§3). Bean **separado** de [BlockchainPoller] pela mesma razão do padrão de reindexação já
 * documentado — `@Transactional` não funciona via self-invocation em proxies Spring.
 */
@Component
class IngestionBatchProcessor(
    private val blockchain: BlockchainPort,
    private val abiDecoding: AbiDecodingPort,
    private val events: BlockchainEventPort,
    private val cursor: IngestionCursorPort,
    private val indexerProperties: IndexerProperties,
    private val balanceProjectionService: BalanceProjectionService,
    private val webhookMatchingService: WebhookMatchingService,
) {
    private val logger = LoggerFactory.getLogger(IngestionBatchProcessor::class.java)

    /**
     * Para cada log: pula se já existe (idempotência por `tx_hash`+`log_index`); memoiza
     * `getBlockTimestamp` por bloco distinto da janela (nunca por evento —
     * `performance-and-ops-notes.md`); decodifica via [AbiDecodingPort] — `null` vira
     * `decoded=false` (regra "nenhum evento é descartado"), não-nulo vira `decoded=true` com
     * `eventName`/`decodedParameters`. O cursor só avança **depois** do `saveAll`, na mesma transação.
     */
    @Transactional
    fun ingestRange(from: Long, to: Long, logs: List<BlockchainLog>) {
        val blockTimestamps = mutableMapOf<Long, Instant>()
        val toSave = mutableListOf<BlockchainEvent>()

        for (log in logs) {
            if (events.existsByTxHashAndLogIndex(log.txHash, log.logIndex)) {
                continue
            }
            val timestamp = blockTimestamps.getOrPut(log.blockNumber) { blockchain.getBlockTimestamp(log.blockNumber) }
            val decoded = abiDecoding.decode(
                contractAddress = log.contractAddress.lowercase(),
                blockNumber = log.blockNumber,
                log = RawLog(topics = log.topics, data = log.data),
            )

            toSave += BlockchainEvent(
                id = UUID.randomUUID(),
                contractAddress = log.contractAddress.lowercase(),
                network = indexerProperties.network,
                blockNumber = log.blockNumber,
                blockHash = log.blockHash,
                txHash = log.txHash,
                logIndex = log.logIndex,
                topic0 = log.topics.firstOrNull(),
                rawTopics = log.topics,
                rawData = log.data,
                decoded = decoded != null,
                eventName = decoded?.eventName,
                decodedParameters = decoded?.parameters,
                blockTimestamp = timestamp,
                createdAt = Instant.now(),
            )
        }

        if (toSave.isNotEmpty()) {
            events.saveAll(toSave)
            // E7: projeta saldo só pros eventos recém-inseridos desta janela (os já existentes foram
            // pulados pela idempotência acima, não precisam reprojetar).
            balanceProjectionService.projectBatch(toSave)
            // E8: casa contra webhooks ativos e enfileira as entregas que baterem — mesma transação,
            // nenhuma chamada HTTP acontece aqui (isso é WebhookDispatchJob, fora da ingestão).
            webhookMatchingService.matchAndEnqueue(toSave)
        }
        cursor.advance(to)
        logger.debug(
            "Janela [{}..{}] ingerida: {} logs recebidos, {} eventos novos (resto já existia — idempotência).",
            from,
            to,
            logs.size,
            toSave.size,
        )
    }
}
