package br.com.bbchain.evex.indexer.domain.model

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * O registro acumulado de **uma** entidade lógica de um [EntityModel] — vai sendo preenchido aos
 * poucos, um [ModelContributor] de cada vez, conforme os eventos chegam (possivelmente dias de
 * diferença entre um contribuinte e outro). Tabela `entity_model_records`
 * (`model_id`, `correlation_value`, `network`, `data` JSONB, `updated_at`), chave única
 * `(model_id, correlation_value, network)` — mesmo padrão de JSONB já usado em
 * `contract_abis`/`blockchain_event`.
 *
 * [data] é o acumulado de todos os `ModelFieldMapping.modelField` já contribuídos até agora — nunca o
 * conjunto completo garantido (um contribuinte que ainda não chegou simplesmente não aparece).
 */
data class EntityModelRecord(
    val modelId: UUID,
    val correlationValue: String,
    val network: Network,
    val data: Map<String, Any?>,
    val updatedAt: Instant,
)
