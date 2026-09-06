package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.wallet.WalletStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import java.util.UUID
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de carteira cadastrada (E22). Espelha [MonitoredContractEntity] 1:1 — mesmo motivo
 * pro `Persistable<UUID>` (`id` gerado na `application`, não pelo banco; sem isso o `isNew()` default
 * do Spring Data sempre tentaria `UPDATE` em vez de `INSERT`, DEC-018).
 */
@Entity
@Table(name = "monitored_wallets", uniqueConstraints = [UniqueConstraint(columnNames = ["address"])])
class WalletEntity(
    @Id
    private val id: UUID,
    @Column(nullable = false, unique = true, length = 42)
    val address: String,
    @Column(nullable = false)
    var label: String,
    @Column
    var description: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: WalletStatus,
    @Column(name = "looks_like_contract", nullable = false)
    var looksLikeContract: Boolean,
    @Column(name = "looks_like_contract_acknowledged", nullable = false)
    var looksLikeContractAcknowledged: Boolean,
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    // `id` é private de propósito: uma propriedade Kotlin pública `id` geraria um `getId()` público
    // que colide na JVM com este override (mesma assinatura apagada `getId()`) — ver MonitoredContractEntity.
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
