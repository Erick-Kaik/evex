package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.application.block.BlockResolutionDirection
import br.com.bbchain.evex.indexer.application.block.BlockResolutionService
import br.com.bbchain.evex.indexer.domain.block.BlockResolutionFailedException
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockStrategies
import br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentBlockSpecException
import org.springframework.stereotype.Component

/**
 * Valida o modo de bloco de enriquecimento pedido e, em `BLOCK_AT_DATE`, converte a data em número de bloco
 * por `FLOOR` (estado do ÚLTIMO bloco até a data) UMA vez — compartilhado por preview e reenvio.
 *
 * - Modo desconhecido ou spec inválida para o modo -> [InvalidEnrichmentBlockSpecException] (400), antes de
 *   qualquer trabalho.
 * - Data não convertível (ex.: anterior ao gênesis) -> [BlockResolutionFailedException] (422).
 */
@Component
class EnrichmentBlockResolver(
    private val blockResolution: BlockResolutionService,
    private val blockStrategies: EnrichmentBlockStrategies,
) {
    /** Devolve a spec efetiva (`BLOCK_AT_DATE` já com o número) e o bloco resolvido para a resposta. */
    fun resolve(requested: EnrichmentBlockSpec): Pair<EnrichmentBlockSpec, PreviewEnrichmentBlock> {
        blockStrategies.strategyFor(requested)
        if (requested.mode != EnrichmentBlockSpec.BLOCK_AT_DATE) {
            blockStrategies.resolve(requested, eventBlockNumber = 0L) // valida os campos do modo (ex.: BLOCK_NUMBER sem número)
            val fixed = requested.mode == EnrichmentBlockSpec.BLOCK_NUMBER
            return requested to PreviewEnrichmentBlock(requested.mode, if (fixed) requested.blockNumber else null, null, null, null, emptyList())
        }
        val date = requested.blockDate
            ?: throw InvalidEnrichmentBlockSpecException("Modo '${requested.mode}' exige blockDate")
        val resolved = blockResolution.resolve(date, BlockResolutionDirection.FLOOR)
        val number = resolved.blockNumber
            ?: throw BlockResolutionFailedException(
                "Não foi possível resolver um bloco para $date (${resolved.warnings.joinToString().ifEmpty { "sem detalhes" }})",
            )
        val effective = requested.copy(blockNumber = number)
        return effective to PreviewEnrichmentBlock(requested.mode, number, date, resolved.approximate, resolved.source, resolved.warnings)
    }
}
