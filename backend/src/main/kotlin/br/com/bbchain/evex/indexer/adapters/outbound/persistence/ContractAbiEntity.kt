package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de uma versão de ABI (`contract_abis`, DEC-007 — várias linhas por
 * `contract_address`, uma por faixa de validade de blocos). Mesmo padrão `Persistable<UUID>` de
 * [MonitoredContractEntity] (`id` gerado em `application`, não pelo banco — ver DEC-018): `id`
 * privado + `getId()` explícito, flag `new` transiente setada pelo adapter via `existsById`.
 *
 * Sem `FOREIGN KEY` formal para `monitored_contracts.address` — mesma decisão implícita já usada
 * pelo resto do projeto (E2 não usa FK entre módulos); `contract_address` só indexado.
 */
@Entity
@Table(name = "contract_abis", indexes = [Index(name = "idx_contract_abis_address", columnList = "contract_address")])
class ContractAbiEntity(
    @Id
    private val id: UUID,
    @Column(name = "contract_address", nullable = false, length = 42)
    val contractAddress: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val source: AbiSource,
    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type", nullable = false, length = 16)
    val storageType: AbiStorageType,
    // JSONB (doc de produto: "PostgreSQL 16 (JSONB para ABIs)") — só preenchido quando storageType
    // é DATABASE ou HYBRID; nulo quando o conteúdo mora só em LOCAL.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "abi_content")
    val abiContent: String? = null,
    @Column(name = "storage_path")
    val storagePath: String? = null,
    @Column(name = "valid_from_block", nullable = false)
    val validFromBlock: Long,
    @Column(name = "valid_to_block")
    var validToBlock: Long? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Transient
    private val new: Boolean = false,
) : Persistable<UUID> {
    // `id` privado por causa do choque de assinatura JVM entre `override val id` e `getId()` -
    // mesma explicação de MonitoredContractEntity (DEC-018).
    override fun getId(): UUID = id

    override fun isNew(): Boolean = new
}
