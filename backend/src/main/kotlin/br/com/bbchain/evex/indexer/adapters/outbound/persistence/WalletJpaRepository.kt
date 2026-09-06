package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WalletJpaRepository : JpaRepository<WalletEntity, UUID> {
    fun findByAddress(address: String): WalletEntity?

    fun findByNetwork(network: Network, pageable: Pageable): Page<WalletEntity>

    /** Projeção leve pra [br.com.bbchain.evex.indexer.domain.wallet.WalletPort.findAllAddresses] — só endereços, sem montar [WalletEntity] inteiro. */
    @Query("SELECT w.address FROM WalletEntity w WHERE (:network IS NULL OR w.network = :network)")
    fun findAllAddressesBy(@Param("network") network: Network?): List<String>
}
