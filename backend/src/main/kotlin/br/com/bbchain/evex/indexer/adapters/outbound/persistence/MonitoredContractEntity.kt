package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de contrato monitorado. `id` é um `UUID` gerado na camada `application`
 * ([br.com.bbchain.evex.indexer.application.contract.MonitoredContractService], não pelo banco), o que
 * faz o `isNew()` default do Spring Data (baseado em `id == null`) sempre achar que a entidade já existe
 * e transformar todo `save()` num `UPDATE`/merge em vez de `INSERT`. Fix: implementar [Persistable] com
 * uma flag transiente `isNew`, setada explicitamente pelo adapter (`JpaMonitoredContractAdapter`) via
 * `findById` antes de decidir insert vs. update. Ver docs/decisions.md DEC-018.
 *
 * `lineageId`/`createdAt` são aditivos da E29/DEC-096/DEC-097 (ver KDoc do domínio). `lineageId` é
 * nullable sem exigir migration (`ddl-auto: update` lida bem com coluna nova nullable numa tabela com
 * linhas); `createdAt` é `NOT NULL` e por isso precisa de `V5__monitored_contracts_lineage_and_created_at.sql`
 * (mesmo gotcha da DEC-041/DEC-083 — Postgres recusa `ALTER TABLE ... ADD COLUMN ... NOT NULL` sem
 * default contra uma tabela já com linhas).
 */
@Entity
@Table(
    name = "monitored_contracts",
    uniqueConstraints = [UniqueConstraint(columnNames = ["address"])],
    indexes = [Index(name = "idx_monitored_contracts_lineage_id", columnList = "lineage_id")],
)
class MonitoredContractEntity(
    @Id
    private val id: UUID,
    @Column(nullable = false, unique = true, length = 42)
    val address: String,
    @Column(nullable = false)
    var name: String,
    @Column
    var description: String? = null,
    @Column
    var version: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ContractStatus,
    @Column(name = "lineage_id")
    var lineageId: UUID? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    // `id` is private on purpose: a public Kotlin property named `id` would generate a public
    // `getId()` accessor that clashes on the JVM with this explicit override (both compile to the
    // same erased signature `getId()`). Keeping `id` private means Kotlin only emits this override.
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
