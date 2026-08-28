package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.Network
import java.math.BigInteger
import java.time.LocalDate

/**
 * Saldo consolidado de uma carteira numa data específica (`asset_balance_snapshots`, doc §4.4) —
 * mesma chave natural de [AssetBalance] + [snapshotDate], gerado pelo job noturno
 * (`application/balance/BalanceSnapshotJob`).
 */
data class AssetBalanceSnapshot(
    val walletAddress: String,
    val contractAddress: String,
    val network: Network,
    val tokenId: String?,
    val balance: BigInteger,
    val snapshotDate: LocalDate,
    val blockNumber: Long,
)
