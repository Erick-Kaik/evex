package br.com.bbchain.evex.indexer.domain.model

import java.util.UUID

/** Nenhum modelo de entidade cadastrado com este id. */
class EntityModelNotFoundException(val id: UUID) : RuntimeException("Modelo de entidade não encontrado: $id")
