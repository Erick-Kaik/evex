package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventCursor
import br.com.bbchain.evex.indexer.domain.event.EventScanFilter
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.matches
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Códigos de aviso extensíveis de `matching-events`. */
object WebhookMatchingEventsWarnings {
    /** Sufixo `:<n>` = quantos eventos sem `blockTimestamp` ficaram fora do filtro por data. */
    const val DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP = "DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP"
}

data class MatchingEventsPage(
    val events: List<BlockchainEvent>,
    /** `null` = varredura chegou ao fim; senão, cursor da próxima página (válido também com [scanTruncated]). */
    val nextCursor: String?,
    val scanned: Int,
    val scanTruncated: Boolean,
    val filtersApplied: Boolean,
    val warnings: List<String>,
)

/**
 * Seleção dos eventos indexados que CASAM com um webhook (E33): contrato + evento + rede + `decoded = true`
 * + janela de bloco/data aplicados em SQL ([BlockchainEventPort.scanDecoded]), e os `filters` do webhook
 * avaliados **em memória** (`WebhookFilterCondition.matches`; não há equivalente SQL sobre o JSONB).
 *
 * Varre da mais recente para a mais antiga, em lotes de [BATCH_SIZE], acumulando até `size` casados ou
 * até `max-scan` eventos varridos (`indexer.webhook.resend.max-scan`) — neste caso `scanTruncated = true`.
 * O `nextCursor` aponta para o último evento CONSUMIDO (não o último devolvido): retomar dele nunca repete
 * nem pula evento. Mesmo critério de erro do disparo normal: filtro que lança exceção = não casa.
 *
 * `nextCursor` só é `null` quando a varredura esgotou os dados; ao fechar a página exatamente no fim dos
 * dados, a página seguinte volta vazia com `nextCursor = null` (não há "peek" para evitar a consulta extra).
 */
@Service
class WebhookMatchingEventsService(
    private val webhookConfigs: WebhookConfigPort,
    private val events: BlockchainEventPort,
    private val properties: WebhookResendProperties,
) {
    @Transactional(readOnly = true)
    fun listMatchingEvents(
        webhookId: UUID,
        fromBlock: Long?,
        toBlock: Long?,
        fromDate: Instant?,
        toDate: Instant?,
        cursor: String?,
        size: Int,
    ): MatchingEventsPage {
        val config = webhookConfigs.findById(webhookId) ?: throw WebhookConfigNotFoundException(webhookId)
        val filter = EventScanFilter(
            contractAddress = config.contractAddress.lowercase(),
            eventName = config.eventName,
            network = config.network,
            fromBlock = fromBlock,
            toBlock = toBlock,
            fromDate = fromDate,
            toDate = toDate,
        )
        var position: EventCursor? = cursor?.let(EventCursor::parse)
        val maxScan = properties.maxScan
        val matched = mutableListOf<BlockchainEvent>()
        var scanned = 0
        var exhausted = false
        var stoppedBySize = false

        scan@ while (true) {
            if (scanned >= maxScan) break
            val limit = minOf(BATCH_SIZE, maxScan - scanned)
            val batch = events.scanDecoded(filter, position, limit)
            for (event in batch) {
                scanned++
                position = EventCursor(event.blockNumber, event.logIndex)
                if (matches(config, event)) {
                    matched += event
                    if (matched.size >= size) {
                        stoppedBySize = true
                        break@scan
                    }
                }
            }
            if (batch.size < limit) {
                exhausted = true
                break
            }
        }

        val truncated = !exhausted && !stoppedBySize
        val warnings = mutableListOf<String>()
        if (fromDate != null || toDate != null) {
            val withoutTimestamp = events.countDecodedWithoutTimestamp(filter)
            if (withoutTimestamp > 0) {
                warnings += "${WebhookMatchingEventsWarnings.DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP}:$withoutTimestamp"
            }
        }
        return MatchingEventsPage(
            events = matched,
            nextCursor = if (exhausted) null else position?.toToken(),
            scanned = scanned,
            scanTruncated = truncated,
            filtersApplied = config.filters.isNotEmpty(),
            warnings = warnings,
        )
    }

    private fun matches(config: WebhookConfig, event: BlockchainEvent): Boolean =
        try {
            val parameters = event.decodedParameters.orEmpty()
            config.filters.all { it.matches(parameters) }
        } catch (ex: Exception) {
            false
        }

    private companion object {
        const val BATCH_SIZE = 500
    }
}
