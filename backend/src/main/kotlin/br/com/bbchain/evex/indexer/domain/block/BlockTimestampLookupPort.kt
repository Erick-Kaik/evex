package br.com.bbchain.evex.indexer.domain.block

import java.time.Instant

/** Um bloco conhecido com seu timestamp. */
data class BlockPoint(val blockNumber: Long, val blockTimestamp: Instant)

/**
 * Par de blocos **ingeridos** (com evento) que cerca um instante: [lower] = o de MAIOR timestamp
 * `<= instant` (em empate de timestamp, o de maior número de bloco) e [upper] = o de MENOR timestamp
 * `>= instant` (em empate, o de menor número). Qualquer lado é `null` se não existe. Se um bloco
 * tem timestamp exatamente igual ao instante, ele pode aparecer nos dois lados.
 */
data class BlockBracket(val lower: BlockPoint?, val upper: BlockPoint?)

/**
 * Porta de leitura sobre os eventos já ingeridos para a conversão data -> bloco
 * (`BlockResolutionService`): permite achar o intervalo de blocos que cerca uma data a custo de 1
 * consulta, sem RPC. Só enxerga blocos que tiveram log ingerido com `blockTimestamp` preenchido —
 * por isso é **aproximação** (blocos sem logs não aparecem). Implementação JPA fica em
 * `adapters/outbound/persistence` (fatia 2 — requer índice em `block_timestamp`).
 */
interface BlockTimestampLookupPort {
    fun findBlockBracket(instant: Instant): BlockBracket
}
