package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface IngestionCursorJpaRepository : JpaRepository<IngestionCursorEntity, Int>
