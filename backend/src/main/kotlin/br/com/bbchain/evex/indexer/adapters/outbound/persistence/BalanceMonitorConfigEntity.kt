package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.common.Network
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de configuração de monitoramento de saldo (`balance_monitor_configs`, doc §4.4). Mesmo
 * padrão `Persistable<UUID>` de [MonitoredContractEntity]/[ContractAbiEntity] (`id` gerado em
 * `application`, não pelo banco — DEC-018): `id` privado + `getId()` explícito, flag `new` transiente
 * setada pelo adapter via `existsById`.
 */
@Entity
@Table(
    name = "balance_monitor_configs",
    indexes = [Index(name = "idx_balance_monitor_configs_lookup", columnList = "contract_address,event_name,active")],
)
class BalanceMonitorConfigEntity(
    @Id
    private val id: UUID,
    @Column(name = "contract_address", nullable = false, length = 42)
    val contractAddress: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Column(name = "event_name", nullable = false)
    val eventName: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val behavior: BalanceBehavior,
    @Column(name = "wallet_param")
    val walletParam: String? = null,
    @Column(name = "from_param")
    val fromParam: String? = null,
    @Column(name = "to_param")
    val toParam: String? = null,
    @Column(name = "value_param", nullable = false)
    val valueParam: String,
    @Column(name = "token_id_param")
    val tokenIdParam: String? = null,
    @Column(nullable = false)
    var active: Boolean,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    // E14/DEC-066 — backfill de saldo sob demanda. Marca d'água imutável + cursor de progresso
    // resumível. Migration V2 (`ADD COLUMN IF NOT EXISTS ... DEFAULT -1`) cobre bancos preexistentes.
    @Column(name = "backfill_watermark_block", nullable = false)
    val backfillWatermarkBlock: Long = -1,
    @Column(name = "backfill_cursor_block", nullable = false)
    val backfillCursorBlock: Long = -1,
    @Column(name = "backfill_cursor_log_index", nullable = false)
    val backfillCursorLogIndex: Long = -1,
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    // `id` privado por causa do choque de assinatura JVM entre `override val id` e `getId()` —
    // mesma explicação de MonitoredContractEntity (DEC-018).
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
