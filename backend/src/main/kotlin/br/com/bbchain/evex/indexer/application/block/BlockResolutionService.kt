package br.com.bbchain.evex.indexer.application.block

import br.com.bbchain.evex.indexer.config.BlockResolutionProperties
import br.com.bbchain.evex.indexer.domain.block.BlockBracket
import br.com.bbchain.evex.indexer.domain.block.BlockPoint
import br.com.bbchain.evex.indexer.domain.block.BlockTimestampLookupPort
import br.com.bbchain.evex.indexer.domain.block.InvalidBlockDirectionException
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Sentido da conversão data -> bloco — strings extensíveis, não enum fechado. */
object BlockResolutionDirection {
    /** Último bloco com `timestamp <= data` (uso: `toDate`, `BLOCK_AT_DATE`). */
    const val FLOOR = "FLOOR"

    /** Primeiro bloco com `timestamp >= data` (uso: `fromDate`). */
    const val CEILING = "CEILING"

    val SUPPORTED = setOf(FLOOR, CEILING)
}

object BlockResolutionSource {
    /** Só consulta aos eventos ingeridos (nenhuma chamada RPC). */
    const val INGESTED = "INGESTED"

    /** Só RPC (nada ingerido ajudou). */
    const val RPC = "RPC"

    /** Intervalo dos ingeridos refinado por bisseção RPC. */
    const val COMBINED = "COMBINED"
}

object BlockResolutionWarnings {
    const val DATE_BEFORE_GENESIS = "DATE_BEFORE_GENESIS"
    const val DATE_AFTER_LATEST = "DATE_AFTER_LATEST"
    const val RPC_UNAVAILABLE_USING_INGESTED = "RPC_UNAVAILABLE_USING_INGESTED"
    const val RPC_BUDGET_EXHAUSTED = "RPC_BUDGET_EXHAUSTED"
    const val INGESTED_LOOKUP_FAILED = "INGESTED_LOOKUP_FAILED"
    const val NO_INGESTED_DATA = "NO_INGESTED_DATA"
}

/**
 * Resultado da conversão. [blockNumber] é `null` quando não existe bloco no sentido pedido (ex.: `FLOOR`
 * antes do gênesis, `CEILING` depois do topo) — sempre com um aviso em [warnings].
 */
data class ResolvedBlock(
    val blockNumber: Long?,
    val blockTimestamp: Instant?,
    val source: String,
    val approximate: Boolean,
    val warnings: List<String>,
)

/**
 * Conversão data -> bloco por estratégia combinada: (1) [BlockTimestampLookupPort] dá o intervalo de
 * blocos ingeridos que cerca a data (1 consulta, sem RPC); (2) se o intervalo ainda não é adjacente,
 * bisseção por [BlockchainPort.getBlockTimestamp] dentro dele (ou de `[0, tip]` se não há ingeridos),
 * com teto de [maxRpcCalls] chamadas RPC no total (inclui `currentBlockNumber`); (3) sem RPC
 * (indisponível/teto estourado) degrada para o limite ingerido com `approximate = true`.
 *
 * Premissas: timestamps de bloco **não decrescentes** (vale para QBFT/IBFT2 e Hardhat). Desempate de
 * timestamps repetidos: `FLOOR` devolve o MAIOR bloco com `timestamp <= data`; `CEILING` devolve o MENOR
 * bloco com `timestamp >= data`. Limites: `FLOOR` antes do gênesis -> sem bloco + `DATE_BEFORE_GENESIS`;
 * `CEILING` antes do gênesis -> bloco 0 + aviso; `FLOOR` depois do topo -> o topo + `DATE_AFTER_LATEST`;
 * `CEILING` depois do topo -> sem bloco + `DATE_AFTER_LATEST`. Só ingeridos = aproximação (blocos sem
 * logs não aparecem; eventos sem `blockTimestamp` são ignorados pela porta).
 *
 * `FLOOR`/`CEILING` reduzem-se a "último bloco `n` com `p(n)`" sobre `p` monótona (`FLOOR`: `ts <= data`;
 * `CEILING`: `ts < data`, resposta = esse + 1).
 */
@Service
class BlockResolutionService(
    private val blockchain: BlockchainPort,
    // Sem bean da porta (implementação JPA chega na fatia 2) o serviço sobe e trabalha só por RPC.
    private val lookup: BlockTimestampLookupPort? = null,
    properties: BlockResolutionProperties = BlockResolutionProperties(),
) {
    private val logger = LoggerFactory.getLogger(BlockResolutionService::class.java)
    private val maxRpcCalls = properties.maxRpcCalls

    fun resolve(date: Instant, direction: String = BlockResolutionDirection.FLOOR): ResolvedBlock {
        if (direction !in BlockResolutionDirection.SUPPORTED) {
            throw InvalidBlockDirectionException(
                "direction desconhecida '$direction' — suportadas: ${BlockResolutionDirection.SUPPORTED.sorted().joinToString()}",
            )
        }
        val floor = direction == BlockResolutionDirection.FLOOR
        val warnings = mutableListOf<String>()
        val known = HashMap<Long, Instant>() // cache bloco -> timestamp (ingeridos + RPC)

        val bracket = try {
            lookup?.findBlockBracket(date) ?: BlockBracket(null, null)
        } catch (ex: Exception) {
            warnings += BlockResolutionWarnings.INGESTED_LOOKUP_FAILED
            BlockBracket(null, null)
        }
        bracket.lower?.let { known[it.blockNumber] = it.blockTimestamp }
        bracket.upper?.let { known[it.blockNumber] = it.blockTimestamp }
        val usedIngested = bracket.lower != null || bracket.upper != null
        if (!usedIngested && lookup != null && BlockResolutionWarnings.INGESTED_LOOKUP_FAILED !in warnings) {
            warnings += BlockResolutionWarnings.NO_INGESTED_DATA
        }

        // p(n) true  <=> n está "antes" da resposta. Limites conhecidos pelos ingeridos:
        //   lo = maior bloco com p true conhecido; hiFalse = menor bloco com p false conhecido.
        var lo: Long? = null
        var hiFalse: Long? = null
        if (floor) {
            bracket.lower?.let { lo = it.blockNumber } // ts <= data
            bracket.upper?.takeIf { it.blockTimestamp > date }?.let { hiFalse = it.blockNumber } // ts > data
        } else {
            bracket.lower?.takeIf { it.blockTimestamp < date }?.let { lo = it.blockNumber } // ts < data
            bracket.upper?.let { hiFalse = it.blockNumber } // ts >= data
        }
        fun p(ts: Instant) = if (floor) ts <= date else ts < date

        var rpcCalls = 0
        var rpcUsed = false
        var degraded = false

        // Executa uma chamada RPC respeitando o teto; devolve null (e marca degradação) se estourou/indisponível.
        fun <T> rpc(block: () -> T): T? {
            if (rpcCalls >= maxRpcCalls) {
                if (BlockResolutionWarnings.RPC_BUDGET_EXHAUSTED !in warnings) warnings += BlockResolutionWarnings.RPC_BUDGET_EXHAUSTED
                degraded = true
                return null
            }
            rpcCalls++
            return try {
                block().also { rpcUsed = true }
            } catch (ex: Exception) {
                // Mensagem do nó pode conter detalhes de infraestrutura — só o tipo vai para o log.
                logger.debug("RPC indisponível na resolução data->bloco ({})", ex.javaClass.simpleName)
                if (BlockResolutionWarnings.RPC_UNAVAILABLE_USING_INGESTED !in warnings) warnings += BlockResolutionWarnings.RPC_UNAVAILABLE_USING_INGESTED
                degraded = true
                null
            }
        }
        fun timestampOf(block: Long): Instant? = known[block] ?: rpc { blockchain.getBlockTimestamp(block) }?.also { known[block] = it }

        fun finish(answer: Long?, forceApproximate: Boolean = false): ResolvedBlock {
            val source = when {
                !rpcUsed -> BlockResolutionSource.INGESTED
                usedIngested -> BlockResolutionSource.COMBINED
                else -> BlockResolutionSource.RPC
            }
            return ResolvedBlock(
                blockNumber = answer,
                blockTimestamp = answer?.let { known[it] },
                source = source,
                approximate = forceApproximate || degraded,
                warnings = warnings.toList(),
            )
        }

        // Resposta a partir de lo/hiFalse convergidos: FLOOR = lo; CEILING = hiFalse (= lo + 1).
        fun answerFrom(lo: Long, hiFalse: Long): Long = if (floor) lo else hiFalse

        if (lo != null && hiFalse != null && hiFalse!! - lo!! <= 1) return finish(answerFrom(lo!!, hiFalse!!))

        // Sem RPC útil: limite ingerido, aproximado. (O RPC é tentado primeiro; só cai aqui se falhar.)
        fun degradedAnswer(): ResolvedBlock {
            degraded = true
            if (BlockResolutionWarnings.RPC_UNAVAILABLE_USING_INGESTED !in warnings && BlockResolutionWarnings.RPC_BUDGET_EXHAUSTED !in warnings) {
                warnings += BlockResolutionWarnings.RPC_UNAVAILABLE_USING_INGESTED
            }
            val best = if (floor) lo else hiFalse
            return finish(best, forceApproximate = true)
        }

        // Limite inferior: precisa de um bloco com p true; senão checa o gênesis.
        if (lo == null) {
            val ts0 = timestampOf(0) ?: return degradedAnswer()
            if (!p(ts0)) {
                // Nenhum bloco satisfaz p: FLOOR não tem bloco; CEILING = gênesis.
                warnings += BlockResolutionWarnings.DATE_BEFORE_GENESIS
                return finish(if (floor) null else 0L)
            }
            lo = 0
        }
        // Limite superior: precisa de um bloco com p false; senão checa o topo.
        if (hiFalse == null) {
            val tip = rpc { blockchain.currentBlockNumber() } ?: return degradedAnswer()
            val tipTs = timestampOf(tip) ?: return degradedAnswer()
            if (p(tipTs)) {
                // Todo bloco satisfaz p: FLOOR = topo; CEILING não tem bloco ainda.
                warnings += BlockResolutionWarnings.DATE_AFTER_LATEST
                return finish(if (floor) tip else null)
            }
            hiFalse = tip
        }

        var low: Long = lo!!
        var high: Long = hiFalse!!
        while (high - low > 1) {
            val mid = low + (high - low) / 2
            // Estado parcial da bisseção vale mais que o limite ingerido original.
            val ts = timestampOf(mid) ?: return finish(if (floor) low else high, forceApproximate = true)
            if (p(ts)) low = mid else high = mid
        }
        if (timestampOf(answerFrom(low, high)) == null) degraded = true
        return finish(answerFrom(low, high))
    }
}
