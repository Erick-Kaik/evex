package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID

/**
 * Entidade JPA de saldo consolidado diário (`asset_balance_snapshots`, doc §4.4). Mesmo desvio
 * deliberado de [AssetBalanceEntity] — sem `Persistable<UUID>`, escrita só via `@Modifying @Query`
 * nativa em [AssetBalanceJpaRepository.snapshotAll] (1 `INSERT ... SELECT ... ON CONFLICT`, não itera
 * linha a linha em código). `tokenId` com o mesmo sentinela `""` do saldo atual.
 */
@Entity
@Table(
    name = "asset_balance_snapshots",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["wallet_address", "contract_address", "token_id", "network", "snapshot_date"]),
    ],
)
class AssetBalanceSnapshotEntity(
    @Id
    val id: UUID,
    @Column(name = "wallet_address", nullable = false, length = 42)
    val walletAddress: String,
    @Column(name = "contract_address", nullable = false, length = 42)
    val contractAddress: String,
    @Column(name = "token_id", nullable = false)
    val tokenId: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Column(nullable = false, columnDefinition = "numeric(78,0)")
    val balance: BigInteger,
    @Column(name = "snapshot_date", nullable = false)
    val snapshotDate: LocalDate,
    @Column(name = "block_number", nullable = false)
    val blockNumber: Long,
)
