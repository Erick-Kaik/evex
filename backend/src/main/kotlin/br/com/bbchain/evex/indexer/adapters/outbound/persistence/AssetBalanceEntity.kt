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
import java.time.Instant
import java.util.UUID

/**
 * Entidade JPA de saldo atual (`asset_balances`, doc §4.4). **Sem `Persistable<UUID>`** (desvio
 * deliberado do padrão DEC-018) — toda escrita passa por `@Modifying @Query` nativa (`ON CONFLICT`,
 * ver [AssetBalanceJpaRepository.upsertDelta]), nunca por `repository.save()`: não há "decidir insert
 * vs. update" via `isNew()` aqui, o Postgres decide via `ON CONFLICT`.
 *
 * `tokenId` mapeado como `String` **não-nulo**, sentinela `""` = "não aplicável" (ex.: ERC20) — achado
 * #2 de `docs/roadmap.md` (E7): `NULL` numa coluna de `UNIQUE`/`ON CONFLICT` nunca colide consigo mesmo
 * no Postgres (`NULL <> NULL`), então um `token_id` nullable faria cada delta ERC20 criar uma linha
 * **nova** em vez de somar na existente. Conversão `domain (String?) ↔ coluna (String, "" = null)` só
 * em [JpaAssetBalanceAdapter].
 */
@Entity
@Table(
    name = "asset_balances",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["wallet_address", "contract_address", "token_id", "network"]),
    ],
)
class AssetBalanceEntity(
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
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)
