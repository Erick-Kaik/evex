package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Cursor de indexação — **linha única** (DEC-002, cursor global, não por contrato). `id` é uma
 * constante conhecida ([FIXED_ID]), não gerada — diferente de [MonitoredContractEntity]/
 * [ContractAbiEntity], não precisa do padrão `Persistable<UUID>` (DEC-018): `save()` sempre usa
 * `EntityManager.merge()` (porque o `id` nunca é `null`), e `merge()` faz upsert corretamente mesmo
 * sem linha existente (Hibernate faz `SELECT` + `INSERT` quando não encontra, não lança erro).
 */
@Entity
@Table(name = "ingestion_cursor")
class IngestionCursorEntity(
    @Id
    val id: Int = FIXED_ID,
    @Column(name = "last_processed_block", nullable = false)
    var lastProcessedBlock: Long,
) {
    companion object {
        const val FIXED_ID = 1
    }
}
