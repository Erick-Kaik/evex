package br.com.bbchain.evex.indexer.application.model

import br.com.bbchain.evex.indexer.application.abi.ContractEventCatalogService
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyResolver
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelNotFoundException
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.InvalidEntityModelException
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Caso de uso: cadastro, consulta e atualização de Modelos de Entidade (E19/DEC-082). Espelha
 * [br.com.bbchain.evex.indexer.application.enrichment.EnrichmentProfileService] estruturalmente.
 */
@Service
class EntityModelService(
    private val models: EntityModelPort,
    private val eventCatalog: ContractEventCatalogService,
    private val resolver: CorrelationKeyResolver = CorrelationKeyResolver(),
) {
    fun register(name: String, description: String?, network: Network, contributors: List<ModelContributor>): EntityModel {
        validateContributors(contributors)
        val model = EntityModel(
            id = UUID.randomUUID(),
            name = name,
            description = description,
            network = network,
            status = EntityModelStatus.ACTIVE,
            contributors = contributors,
            createdAt = Instant.now(),
        )
        return models.save(model)
    }

    fun getById(id: UUID): EntityModel = models.findById(id) ?: throw EntityModelNotFoundException(id)

    fun list(network: Network?, page: Int, size: Int): PageResult<EntityModel> = models.findAll(network, page, size)

    /** `network` é imutável — só `name`/`description`/`contributors` mudam. */
    fun update(id: UUID, name: String, description: String?, contributors: List<ModelContributor>): EntityModel {
        validateContributors(contributors)
        val existing = getById(id)
        val updated = existing.copy(name = name, description = description, contributors = contributors)
        return models.save(updated)
    }

    fun updateStatus(id: UUID, status: EntityModelStatus): EntityModel {
        val existing = getById(id)
        return models.save(existing.copy(status = status))
    }

    /**
     * Falha cedo (cadastro/edição): cada `(contractAddress, eventName)` de [ModelContributor] precisa
     * existir no catálogo de eventos do contrato (reaproveita [ContractEventCatalogService], E16), e
     * `correlationParam`/partes `PARAM`/cada `ModelFieldMapping.decodedParam` (DEC-111) precisa ser um parâmetro real daquele
     * evento. Um evento pode aparecer mais de uma vez no catálogo (faixas de validade de ABI
     * distintas, DEC-007) — a união dos parâmetros de todas as versões encontradas é aceita (cobre o
     * caso raro de um parâmetro só existir numa versão específica da ABI).
     */
    private fun validateContributors(contributors: List<ModelContributor>) {
        contributors.forEach { contributor ->
            val normalizedAddress = contributor.contractAddress.lowercase()
            val events = try {
                eventCatalog.catalogFor(normalizedAddress)
            } catch (ex: ContractNotFoundException) {
                throw InvalidEntityModelException("contractAddress não cadastrado: ${contributor.contractAddress}")
            }
            val matching = events.filter { it.name == contributor.eventName }
            if (matching.isEmpty()) {
                throw InvalidEntityModelException(
                    "evento '${contributor.eventName}' não encontrado na ABI de ${contributor.contractAddress}",
                )
            }
            val knownParams = matching.flatMap { it.inputs }.map { it.name }.toSet()
            val keyParts = contributor.correlationKey.orEmpty()
            if (keyParts.isEmpty()) {
                val correlationParam = contributor.correlationParam
                    ?: throw InvalidEntityModelException(
                        "contribuinte do evento '${contributor.eventName}' precisa de correlationKey ou correlationParam",
                    )
                if (correlationParam !in knownParams) {
                    throw InvalidEntityModelException(
                        "correlationParam '$correlationParam' não é um parâmetro do evento '${contributor.eventName}'",
                    )
                }
            }
            keyParts.forEach { part ->
                if (!resolver.isKnownKeyPartType(part.type)) {
                    throw InvalidEntityModelException(
                        "tipo de parte de correlationKey desconhecido '${part.type}' no evento '${contributor.eventName}'",
                    )
                }
                if (resolver.keyPartRequiresArg(part.type) && part.arg.isNullOrEmpty()) {
                    throw InvalidEntityModelException(
                        "parte '${part.type}' de correlationKey exige 'arg' (evento '${contributor.eventName}')",
                    )
                }
                if (part.type == "PARAM" && part.arg !in knownParams) {
                    throw InvalidEntityModelException(
                        "correlationKey PARAM '${part.arg}' não é um parâmetro do evento '${contributor.eventName}'",
                    )
                }
            }
            contributor.fieldMappings.forEach { mapping ->
                if (!resolver.isKnownFieldSource(mapping.source)) {
                    throw InvalidEntityModelException(
                        "source desconhecida '${mapping.source}' no mapeamento '${mapping.modelField}' do evento '${contributor.eventName}'",
                    )
                }
                if (mapping.source == CorrelationKeyResolver.PARAM_SOURCE && (mapping.decodedParam.isNullOrEmpty() || mapping.decodedParam !in knownParams)) {
                    throw InvalidEntityModelException(
                        "decodedParam '${mapping.decodedParam}' não é um parâmetro do evento '${contributor.eventName}'",
                    )
                }
            }
        }
        validateArity(contributors)
    }

    /** Todos os contribuintes do mesmo modelo precisam ter o mesmo número de partes (senão as chaves nunca se unem). */
    private fun validateArity(contributors: List<ModelContributor>) {
        val arities = contributors.map { it.correlationKey?.takeIf { key -> key.isNotEmpty() }?.size ?: 1 }.toSet()
        if (arities.size > 1) {
            throw InvalidEntityModelException(
                "todos os contribuintes do modelo precisam ter o mesmo número de partes na chave de correlação (encontrado: ${arities.sorted()})",
            )
        }
    }
}
