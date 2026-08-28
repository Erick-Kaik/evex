package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.balance.AssetBalance
import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.balance.AssetBalanceSnapshot
import br.com.bbchain.evex.indexer.domain.balance.BalanceDelta
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component

/**
 * Implementação JPA/Postgres de [AssetBalancePort]. `applyDeltas` mescla a lista por chave
 * `(walletAddress, contractAddress, tokenId, network)` em memória (`groupBy` + soma `BigInteger`)
 * antes de disparar 1 upsert nativo por chave já mesclada — nunca 1 `UPDATE` por delta individual.
 *
 * `tokenId`: sentinela `""` na coluna (`AssetBalanceEntity`/achado #2 da E7) — conversão pro/do
 * domínio (`String?`) só acontece aqui, na borda.
 */
@Component
class JpaAssetBalanceAdapter(
    private val repository: AssetBalanceJpaRepository,
    private val snapshotRepository: AssetBalanceSnapshotJpaRepository,
) : AssetBalancePort {

    override fun applyDeltas(deltas: List<BalanceDelta>) {
        if (deltas.isEmpty()) return
        deltas.groupBy { BalanceKey(it.walletAddress, it.contractAddress, it.tokenId, it.network) }
            .mapValues { (_, group) -> group.fold(BigInteger.ZERO) { acc, delta -> acc + delta.amount } }
            .forEach { (key, amount) ->
                repository.upsertDelta(
                    id = UUID.randomUUID(),
                    walletAddress = key.walletAddress,
                    contractAddress = key.contractAddress,
                    tokenId = key.tokenId.toStorageTokenId(),
                    network = key.network.name,
                    amount = amount,
                )
            }
    }

    override fun search(
        walletAddress: String?,
        contractAddress: String?,
        tokenId: String?,
        network: Network?,
        page: Int,
        size: Int,
    ): PageResult<AssetBalance> {
        val pageable = PageRequest.of(page, size, Sort.by("walletAddress", "contractAddress", "tokenId"))
        val specification = Specification.allOf(
            listOfNotNull(
                walletAddress?.let { address -> Specification<AssetBalanceEntity> { root, _, cb -> cb.equal(root.get<String>("walletAddress"), address.lowercase()) } },
                contractAddress?.let { address -> Specification<AssetBalanceEntity> { root, _, cb -> cb.equal(root.get<String>("contractAddress"), address.lowercase()) } },
                tokenId?.let { id -> Specification<AssetBalanceEntity> { root, _, cb -> cb.equal(root.get<String>("tokenId"), id) } },
                network?.let { net -> Specification<AssetBalanceEntity> { root, _, cb -> cb.equal(root.get<Network>("network"), net) } },
            ),
        )
        val result = repository.findAll(specification, pageable)
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    override fun searchHistory(
        walletAddress: String?,
        contractAddress: String?,
        tokenId: String?,
        network: Network?,
        date: LocalDate,
        page: Int,
        size: Int,
    ): PageResult<AssetBalanceSnapshot> {
        val pageable = PageRequest.of(page, size, Sort.by("walletAddress", "contractAddress", "tokenId"))
        val specification = Specification.allOf(
            listOfNotNull(
                Specification<AssetBalanceSnapshotEntity> { root, _, cb -> cb.equal(root.get<LocalDate>("snapshotDate"), date) },
                walletAddress?.let { address -> Specification<AssetBalanceSnapshotEntity> { root, _, cb -> cb.equal(root.get<String>("walletAddress"), address.lowercase()) } },
                contractAddress?.let { address -> Specification<AssetBalanceSnapshotEntity> { root, _, cb -> cb.equal(root.get<String>("contractAddress"), address.lowercase()) } },
                tokenId?.let { id -> Specification<AssetBalanceSnapshotEntity> { root, _, cb -> cb.equal(root.get<String>("tokenId"), id) } },
                network?.let { net -> Specification<AssetBalanceSnapshotEntity> { root, _, cb -> cb.equal(root.get<Network>("network"), net) } },
            ),
        )
        val result = snapshotRepository.findAll(specification, pageable)
        return PageResult(
            content = result.content.map { it.toDomain() },
            page = page,
            size = size,
            totalElements = result.totalElements,
        )
    }

    override fun snapshotAll(date: LocalDate, blockNumber: Long): Int = repository.snapshotAll(date, blockNumber)

    private data class BalanceKey(val walletAddress: String, val contractAddress: String, val tokenId: String?, val network: Network)

    private fun String?.toStorageTokenId(): String = this ?: ""

    private fun String.toDomainTokenId(): String? = ifEmpty { null }

    private fun AssetBalanceEntity.toDomain() = AssetBalance(
        walletAddress = walletAddress,
        contractAddress = contractAddress,
        network = network,
        tokenId = tokenId.toDomainTokenId(),
        balance = balance,
        updatedAt = updatedAt,
    )

    private fun AssetBalanceSnapshotEntity.toDomain() = AssetBalanceSnapshot(
        walletAddress = walletAddress,
        contractAddress = contractAddress,
        network = network,
        tokenId = tokenId.toDomainTokenId(),
        balance = balance,
        snapshotDate = snapshotDate,
        blockNumber = blockNumber,
    )
}
