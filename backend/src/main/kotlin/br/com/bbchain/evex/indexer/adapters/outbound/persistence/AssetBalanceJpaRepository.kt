package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * `upsertDelta`/`snapshotAll` são `@Modifying` nativas com `ON CONFLICT` (ver
 * [AssetBalanceEntity]/`docs/roadmap.md` achado #2 da E7) — nunca `repository.save()` nestas 2
 * tabelas. `@Transactional` explícito em cada método: `applyDeltas` normalmente já roda dentro da
 * transação de ingestão/redecode (junta-se a ela), mas `snapshotAll` é chamado pelo `BalanceSnapshotJob`
 * (`@Scheduled`, sem transação própria) — sem essa anotação, Spring Data rejeita uma query `@Modifying`
 * fora de transação.
 *
 * `clearAutomatically = true`: consultas nativas passam por baixo do Hibernate (não atualizam o
 * 1º-level cache/persistence context) — sem isso, um `search`/`searchHistory` que já tivesse
 * carregado a mesma linha antes (mesmo `id`, mesma transação) devolveria o objeto **cacheado**
 * desatualizado em vez de reler do banco. Pego por teste de integração real
 * (`JpaAssetBalanceAdapterTest`, cenário de `snapshotAll` rodado 2x na mesma transação de teste).
 */
interface AssetBalanceJpaRepository : JpaRepository<AssetBalanceEntity, UUID>, JpaSpecificationExecutor<AssetBalanceEntity> {

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        value = """
            INSERT INTO asset_balances (id, wallet_address, contract_address, token_id, network, balance, updated_at)
            VALUES (:id, :walletAddress, :contractAddress, :tokenId, :network, :amount, now())
            ON CONFLICT (wallet_address, contract_address, token_id, network)
            DO UPDATE SET balance = asset_balances.balance + EXCLUDED.balance, updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsertDelta(
        @Param("id") id: UUID,
        @Param("walletAddress") walletAddress: String,
        @Param("contractAddress") contractAddress: String,
        @Param("tokenId") tokenId: String,
        @Param("network") network: String,
        @Param("amount") amount: BigInteger,
    )

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        value = """
            INSERT INTO asset_balance_snapshots (id, wallet_address, contract_address, token_id, network, balance, snapshot_date, block_number)
            SELECT gen_random_uuid(), wallet_address, contract_address, token_id, network, balance, :date, :blockNumber
            FROM asset_balances
            ON CONFLICT (wallet_address, contract_address, token_id, network, snapshot_date)
            DO UPDATE SET balance = EXCLUDED.balance, block_number = EXCLUDED.block_number
        """,
        nativeQuery = true,
    )
    fun snapshotAll(@Param("date") date: LocalDate, @Param("blockNumber") blockNumber: Long): Int

    /**
     * Candidatos brutos pra descoberta automática de carteiras (E22 / DEC-080) — endereços distintos
     * já observados em `asset_balances.wallet_address` (coluna tipada, bem mais barato que vasculhar o
     * JSONB de eventos), sem excluir os já cadastrados em `monitored_wallets` (exclusão em memória, na
     * `application`, ver [br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort.findDistinctWalletAddresses]).
     */
    @Query("SELECT DISTINCT a.walletAddress FROM AssetBalanceEntity a WHERE (:network IS NULL OR a.network = :network)")
    fun findDistinctWalletAddressesBy(@Param("network") network: Network?): List<String>
}
