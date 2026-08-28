package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface AssetBalanceSnapshotJpaRepository :
    JpaRepository<AssetBalanceSnapshotEntity, UUID>,
    JpaSpecificationExecutor<AssetBalanceSnapshotEntity>
