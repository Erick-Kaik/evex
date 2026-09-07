package br.com.bbchain.evex.indexer.domain.model

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Modelo de entidade (E19/DEC-082, "Terceira peça") — acumula campos de eventos **diferentes**, de
 * contratos possivelmente diferentes, que ocorrem em blocos/transações diferentes, sobre a mesma
 * entidade lógica (ex.: um fluxo de mint em 3 etapas: `Created` -> `Approved` -> `Minted`, cada um numa
 * transação separada, todos sobre o mesmo `id`).
 *
 * Diferente de [br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile] (que **lê** estado
 * on-chain sob demanda, recalculado a cada disparo, fora da transação de ingestão): este agregado
 * **persiste** o acumulado ([br.com.bbchain.evex.indexer.domain.model.EntityModelRecord]) e é resolvido
 * **dentro** da mesma transação de ingestão/redecode — não precisa de rede, só banco.
 *
 * `network` (regra "campo `network` em toda entidade") pelo mesmo raciocínio de
 * [br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile]. Sem exclusão real — só
 * arquivamento ([EntityModelStatus.ARCHIVED], mesma regra do resto da API).
 */
data class EntityModel(
    val id: UUID,
    val name: String,
    val description: String?,
    val network: Network,
    val status: EntityModelStatus,
    val contributors: List<ModelContributor>,
    val createdAt: Instant,
)

/**
 * Um evento contribuinte de um [EntityModel] — identifica de qual `(contractAddress, eventName)` vêm
 * os campos, qual parâmetro decodificado *deste* evento identifica a entidade ([correlationParam], ex.:
 * `id` num contrato, `requestId` noutro — por isso é mapeado manualmente por contribuinte, não assumido
 * por nome de campo igual entre eventos), e quais campos decodificados entram no modelo acumulado
 * ([fieldMappings]).
 *
 * Validado no cadastro do [EntityModel] (falhar cedo, mesmo princípio do resto da API): o par
 * `(contractAddress, eventName)` precisa existir no catálogo de eventos do contrato
 * ([br.com.bbchain.evex.indexer.domain.abi.ContractEventCatalogService] — reaproveitado, E16), e
 * [correlationParam]/cada `ModelFieldMapping.decodedParam` precisa ser um parâmetro real daquele
 * evento.
 */
data class ModelContributor(
    val contractAddress: String,
    val eventName: String,
    val correlationParam: String,
    val fieldMappings: List<ModelFieldMapping>,
)

/** Um campo decodificado de um [ModelContributor] mapeado pra um nome de campo do modelo acumulado. */
data class ModelFieldMapping(
    val decodedParam: String,
    val modelField: String,
)
