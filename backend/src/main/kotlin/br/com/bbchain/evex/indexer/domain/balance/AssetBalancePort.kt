package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.time.LocalDate

/**
 * Porta de saída para o saldo atual/histórico de ativos. Implementação JPA/Postgres em
 * `adapters/outbound/persistence/JpaAssetBalanceAdapter` — upsert atômico via `ON CONFLICT`
 * (achado #2 de `docs/roadmap.md` E7: `token_id` mapeado como coluna **não-nula** na tabela, sentinela
 * `''` pra "não aplicável", porque `NULL` nunca colide consigo mesmo em `UNIQUE`/`ON CONFLICT` do
 * Postgres).
 */
interface AssetBalancePort {
    /**
     * Mescla [deltas] por chave `(walletAddress, contractAddress, tokenId, network)` e aplica 1 upsert
     * atômico por chave já mesclada — nunca 1 `UPDATE` por delta individual.
     */
    fun applyDeltas(deltas: List<BalanceDelta>)

    fun search(
        walletAddress: String?,
        contractAddress: String?,
        tokenId: String?,
        network: Network?,
        page: Int,
        size: Int,
    ): PageResult<AssetBalance>

    fun searchHistory(
        walletAddress: String?,
        contractAddress: String?,
        tokenId: String?,
        network: Network?,
        date: LocalDate,
        page: Int,
        size: Int,
    ): PageResult<AssetBalanceSnapshot>

    /** Consolida `asset_balances` em `asset_balance_snapshots` pra [date]/[blockNumber]. Retorna linhas gravadas. */
    fun snapshotAll(date: LocalDate, blockNumber: Long): Int
}
