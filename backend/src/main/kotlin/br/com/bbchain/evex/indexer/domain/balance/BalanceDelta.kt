package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.Network
import java.math.BigInteger

/**
 * Variação de saldo (assinada) produzida por um [BalanceBehaviorStrategy] a partir de 1 evento
 * decodificado. Múltiplos deltas do mesmo lote/mesma chave `(walletAddress, contractAddress, tokenId,
 * network)` são acumulados em memória e mesclados num único upsert atômico pelo adapter
 * (`JpaAssetBalanceAdapter.applyDeltas`) — ver achado #2 de `docs/roadmap.md` (E7) sobre `NULL` em
 * `UNIQUE`/`ON CONFLICT` e o sentinela de `tokenId`.
 */
data class BalanceDelta(
    val walletAddress: String,
    val contractAddress: String,
    val network: Network,
    val tokenId: String?,
    val amount: BigInteger,
)
