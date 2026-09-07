package br.com.bbchain.evex.indexer.domain.enrichment

import java.util.UUID

/** Nenhum perfil de enriquecimento cadastrado com este id. */
class EnrichmentProfileNotFoundException(val id: UUID) : RuntimeException("Perfil de enriquecimento não encontrado: $id")
