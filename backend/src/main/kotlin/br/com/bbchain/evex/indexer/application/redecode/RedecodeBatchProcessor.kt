package br.com.bbchain.evex.indexer.application.redecode

import br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService
import br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService
import br.com.bbchain.evex.indexer.domain.abi.AbiDecodingPort
import br.com.bbchain.evex.indexer.domain.abi.RawLog
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Resultado de 1 lote de redecode — interno ao caso de uso, não é tipo de domínio. */
data class RedecodeBatchResult(
    val processedCount: Int,
    val decodedCount: Int,
)

/**
 * Processa 1 lote de eventos raw pendentes contra [RedecodeFilter] (`ingestion-pipeline-reference.md`
 * §4). Bean **separado** de [RedecodeOrchestrator]/[RedecodeRunner] — mesma razão de self-invocation já
 * documentada pro `@Transactional` (`IngestionBatchProcessor`, E4): `@Transactional` não funciona
 * quando o método é chamado a partir da mesma classe (não passa pelo proxy do Spring).
 *
 * Redecode não toca a rede — o `RawLog` é reconstruído a partir de `rawTopics`/`rawData` já
 * persistidos (`ingestion-pipeline-reference.md`: "logs de blocos confirmados são imutáveis on-chain").
 */
@Component
class RedecodeBatchProcessor(
    private val events: BlockchainEventPort,
    private val abiDecoding: AbiDecodingPort,
    private val balanceProjectionService: BalanceProjectionService,
    private val webhookMatchingService: WebhookMatchingService,
) {
    /**
     * Busca até [batchSize] candidatos (`decoded=false` + [filter]); tenta decodificar cada um contra
     * a ABI vigente no bloco do evento — sucesso vira `copy(decoded=true, ...)`, falha (ABI ainda
     * desconhecida pro `topic0`) continua como está, sem entrar no `updateAll` (regra "aplicar delta
     * uma única vez": nunca revisitar uma linha que já saiu do estado pendente, mas também nunca
     * escrever nas que continuam pendentes — `ingestion-pipeline-reference.md` §5).
     */
    @Transactional
    fun processBatch(filter: RedecodeFilter, batchSize: Int): RedecodeBatchResult {
        val candidates = events.findPendingCandidates(filter, batchSize)
        val changed = candidates.mapNotNull { event ->
            val decoded = abiDecoding.decode(
                contractAddress = event.contractAddress,
                blockNumber = event.blockNumber,
                log = RawLog(topics = event.rawTopics, data = event.rawData),
            ) ?: return@mapNotNull null
            event.copy(decoded = true, eventName = decoded.eventName, decodedParameters = decoded.parameters)
        }
        if (changed.isNotEmpty()) {
            events.updateAll(changed)
            // E7: projeta saldo só pros eventos que acabaram de virar decoded=true neste lote — o
            // cenário central de "cadastrar ABI/config de saldo depois do fato" (doc §4.3/§4.4).
            balanceProjectionService.projectBatch(changed)
            // E8: mesmo raciocínio, agora pra webhooks — cadastrar ABI decodifica o histórico e
            // também dispara os webhooks configurados pra esses eventos que acabaram de decodificar.
            webhookMatchingService.matchAndEnqueue(changed)
        }
        return RedecodeBatchResult(processedCount = candidates.size, decodedCount = changed.size)
    }
}
