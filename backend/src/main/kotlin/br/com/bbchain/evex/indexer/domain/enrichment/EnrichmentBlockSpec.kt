package br.com.bbchain.evex.indexer.domain.enrichment

import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import java.time.Instant

/**
 * Em qual bloco as chamadas de um perfil de enriquecimento são executadas. [mode] é um **código
 * extensível** (string, não enum fechado) resolvido por um [EnrichmentBlockStrategy] registrado em
 * [EnrichmentBlockStrategies] — modo novo = estratégia nova, sem tocar o resolver.
 *
 * Modos embutidos: [EVENT_BLOCK] (padrão — bloco do evento), [LATEST], [BLOCK_NUMBER] (usa
 * [blockNumber]) e [BLOCK_AT_DATE] (usa [blockNumber] **já resolvido por quem chama** a partir de
 * [blockDate]; este tipo só carrega modo/valores — a conversão data -> bloco é do
 * `BlockResolutionService`).
 */
data class EnrichmentBlockSpec(
    val mode: String = EVENT_BLOCK,
    val blockNumber: Long? = null,
    val blockDate: Instant? = null,
) {
    companion object {
        const val EVENT_BLOCK = "EVENT_BLOCK"
        const val LATEST = "LATEST"
        const val BLOCK_NUMBER = "BLOCK_NUMBER"
        const val BLOCK_AT_DATE = "BLOCK_AT_DATE"

        /** Padrão do disparo normal: o `eth_call` roda no bloco em que o evento ocorreu. */
        val EventBlock = EnrichmentBlockSpec(EVENT_BLOCK)
    }
}

/** Modo de bloco desconhecido ou [EnrichmentBlockSpec] inválido para o modo — erro de validação do chamador. */
open class InvalidEnrichmentBlockSpecException(message: String) : RuntimeException(message)

class UnknownEnrichmentBlockModeException(val mode: String, val supported: Set<String>) :
    InvalidEnrichmentBlockSpecException("Modo de bloco de enriquecimento desconhecido '$mode' — suportados: ${supported.sorted().joinToString()}")

/**
 * Estratégia por [mode]: transforma a [EnrichmentBlockSpec] (+ bloco do evento) no [CallBlock] do
 * `eth_call`. Devolve `null` quando o bloco não pode ser determinado para esta entrada (ex.:
 * `EVENT_BLOCK` sem bloco de evento conhecido) — o resolver reporta como falha da chamada, sem
 * derrubar o lote. Spec inválida para o modo (ex.: `BLOCK_NUMBER` sem número) lança
 * [InvalidEnrichmentBlockSpecException].
 */
interface EnrichmentBlockStrategy {
    val mode: String

    fun resolve(eventBlockNumber: Long?, spec: EnrichmentBlockSpec): CallBlock?
}

class EventBlockStrategy : EnrichmentBlockStrategy {
    override val mode = EnrichmentBlockSpec.EVENT_BLOCK
    override fun resolve(eventBlockNumber: Long?, spec: EnrichmentBlockSpec): CallBlock? =
        eventBlockNumber?.let { CallBlock.Number(it) }
}

class LatestBlockStrategy : EnrichmentBlockStrategy {
    override val mode = EnrichmentBlockSpec.LATEST
    override fun resolve(eventBlockNumber: Long?, spec: EnrichmentBlockSpec): CallBlock = CallBlock.Latest
}

/** Bloco fixo informado em [EnrichmentBlockSpec.blockNumber] — serve a `BLOCK_NUMBER` e `BLOCK_AT_DATE` (já resolvido). */
class FixedBlockStrategy(override val mode: String) : EnrichmentBlockStrategy {
    override fun resolve(eventBlockNumber: Long?, spec: EnrichmentBlockSpec): CallBlock {
        val number = spec.blockNumber
            ?: throw InvalidEnrichmentBlockSpecException(
                "Modo '$mode' exige blockNumber" +
                    if (mode == EnrichmentBlockSpec.BLOCK_AT_DATE) " (a data deve ser resolvida a bloco por quem chama antes)" else "",
            )
        if (number < 0) throw InvalidEnrichmentBlockSpecException("blockNumber não pode ser negativo: $number")
        return CallBlock.Number(number)
    }
}

/** Registro `modo -> estratégia`; evita `when` espalhado sobre o modo. */
class EnrichmentBlockStrategies(strategies: Collection<EnrichmentBlockStrategy>) {
    private val byMode: Map<String, EnrichmentBlockStrategy> = strategies.associateBy { it.mode }

    val supportedModes: Set<String> get() = byMode.keys

    /** Valida o modo e devolve o [CallBlock] (ou `null` se indeterminável para esta entrada). */
    fun resolve(spec: EnrichmentBlockSpec, eventBlockNumber: Long?): CallBlock? =
        strategyFor(spec).resolve(eventBlockNumber, spec)

    fun strategyFor(spec: EnrichmentBlockSpec): EnrichmentBlockStrategy =
        byMode[spec.mode] ?: throw UnknownEnrichmentBlockModeException(spec.mode, supportedModes)

    companion object {
        fun builtIn(): List<EnrichmentBlockStrategy> = listOf(
            EventBlockStrategy(),
            LatestBlockStrategy(),
            FixedBlockStrategy(EnrichmentBlockSpec.BLOCK_NUMBER),
            FixedBlockStrategy(EnrichmentBlockSpec.BLOCK_AT_DATE),
        )

        fun default(): EnrichmentBlockStrategies = EnrichmentBlockStrategies(builtIn())
    }
}
