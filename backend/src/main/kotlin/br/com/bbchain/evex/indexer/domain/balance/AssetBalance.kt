package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.Network
import java.math.BigInteger
import java.time.Instant

/**
 * Saldo atual de uma carteira para um `(contractAddress, tokenId)` — resultado acumulado de
 * `AssetBalancePort.applyDeltas`. Sem `id` de propósito: é um agregado por chave natural
 * `(walletAddress, contractAddress, tokenId, network)`, não uma entidade referenciada por id em
 * nenhum outro lugar do domínio.
 */
data class AssetBalance(
    val walletAddress: String,
    val contractAddress: String,
    val network: Network,
    val tokenId: String?,
    val balance: BigInteger,
    val updatedAt: Instant,
)
