package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable

/**
 * Entidade JPA de um log ingerido — raw e decodificado unificados (`ingestion-pipeline-reference.md`
 * §3: "nenhum evento é descartado"). Mesmo padrão `Persistable<UUID>` de [ContractAbiEntity]/
 * [MonitoredContractEntity] (`id` gerado em `application`, não pelo banco — DEC-018).
 *
 * `raw_topics`/`decoded_parameters` são JSONB (`@JdbcTypeCode(SqlTypes.JSON)`), mas mapeados como
 * `String` (JSON já serializado) — mesmo padrão de `ContractAbiEntity.abi_content` (DEC-021): o
 * (de)serialize Kotlin-objeto&lt;-&gt;JSON acontece explicitamente em [JpaBlockchainEventAdapter] (Jackson 2
 * local, DEC-020), não via mapeamento automático do Hibernate para tipos compostos (não exercitado
 * ainda neste projeto, evitado por segurança nesta etapa).
 *
 * Sem `FOREIGN KEY` para `monitored_contracts.address` — mesma decisão implícita já usada pelo resto
 * do projeto (E2/E3 não usam FK entre módulos); `contract_address` só indexado. Sem `@ElementCollection`
 * de `participants` — fora do escopo desta etapa (ver notas de execução da E4 em `docs/roadmap.md`).
 */
@Entity
@Table(
    name = "blockchain_event",
    indexes = [
        Index(name = "idx_blockchain_event_contract_address", columnList = "contract_address"),
        Index(name = "idx_blockchain_event_block_number", columnList = "block_number"),
    ],
    uniqueConstraints = [UniqueConstraint(columnNames = ["tx_hash", "log_index"])],
)
class BlockchainEventEntity(
    @Id
    private val id: UUID,
    @Column(name = "contract_address", nullable = false, length = 42)
    val contractAddress: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val network: Network,
    @Column(name = "block_number", nullable = false)
    val blockNumber: Long,
    @Column(name = "block_hash", nullable = false)
    val blockHash: String,
    @Column(name = "tx_hash", nullable = false)
    val txHash: String,
    @Column(name = "log_index", nullable = false)
    val logIndex: Long,
    @Column(name = "topic0")
    val topic0: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_topics", nullable = false)
    val rawTopics: String,
    @Column(name = "raw_data", nullable = false, columnDefinition = "text")
    val rawData: String,
    @Column(nullable = false)
    val decoded: Boolean,
    @Column(name = "event_name")
    val eventName: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decoded_parameters")
    val decodedParameters: String? = null,
    @Column(name = "block_timestamp")
    val blockTimestamp: Instant? = null,
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
