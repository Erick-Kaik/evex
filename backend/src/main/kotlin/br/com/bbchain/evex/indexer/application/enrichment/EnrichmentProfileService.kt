package br.com.bbchain.evex.indexer.application.enrichment

import br.com.bbchain.evex.indexer.application.abi.ContractMethodCatalogService
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodResolver
import br.com.bbchain.evex.indexer.domain.abi.MethodResolution
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiMethod
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileNotFoundException
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
import br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentProfileException
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/**
 * Caso de uso: cadastro, consulta e atualização de Perfis de Enriquecimento (E19/DEC-082). Espelha
 * [br.com.bbchain.evex.indexer.application.wallet.WalletService] estruturalmente.
 */
@Service
class EnrichmentProfileService(
    private val profiles: EnrichmentProfilePort,
    private val methodCatalog: ContractMethodCatalogService,
) {
    fun register(name: String, description: String?, network: Network, calls: List<EnrichmentCall>): EnrichmentProfile {
        validateCalls(calls)
        val profile = EnrichmentProfile(
            id = UUID.randomUUID(),
            name = name,
            description = description,
            network = network,
            status = EnrichmentProfileStatus.ACTIVE,
            calls = calls,
            createdAt = Instant.now(),
        )
        return profiles.save(profile)
    }

    fun getById(id: UUID): EnrichmentProfile = profiles.findById(id) ?: throw EnrichmentProfileNotFoundException(id)

    fun list(network: Network?, page: Int, size: Int): PageResult<EnrichmentProfile> = profiles.findAll(network, page, size)

    /** `network` é imutável — só `name`/`description`/`calls` mudam. */
    fun update(id: UUID, name: String, description: String?, calls: List<EnrichmentCall>): EnrichmentProfile {
        validateCalls(calls)
        val existing = getById(id)
        val updated = existing.copy(name = name, description = description, calls = calls)
        return profiles.save(updated)
    }

    fun updateStatus(id: UUID, status: EnrichmentProfileStatus): EnrichmentProfile {
        val existing = getById(id)
        return profiles.save(existing.copy(status = status))
    }

    /**
     * Falha cedo (cadastro/edição), nunca silenciosamente no 1º disparo de um webhook que referencia o
     * perfil: cada `targetContractAddress` precisa existir e ter ABI expondo `methodName` como
     * `view`/`pure` (reaproveita [ContractMethodCatalogService], E19/DEC-082), com saídas suficientes
     * pra cobrir todo `EnrichmentOutputMapping.outputIndex` referenciado. Um método pode aparecer mais
     * de uma vez no catálogo (faixas de validade de ABI distintas, DEC-007) — usa a maior contagem de
     * saídas entre as versões encontradas, pra não rejeitar um `outputIndex` que só existe numa versão
     * mais recente. Método escolhido por **nome + aridade** (`args.size`) — ver [AbiMethodResolver].
     */
    private fun validateCalls(calls: List<EnrichmentCall>) {
        calls.forEach { call ->
            val normalizedAddress = call.targetContractAddress.lowercase()
            val methods = try {
                methodCatalog.catalogFor(normalizedAddress)
            } catch (ex: ContractNotFoundException) {
                throw InvalidEnrichmentProfileException("targetContractAddress não cadastrado: ${call.targetContractAddress}")
            }
            // Resolução por nome + aridade (mesma regra do resolver de runtime). Avaliada por VERSÃO de ABI
            // (faixa de blocos, DEC-007): ambiguidade em qualquer versão já é erro (falharia em runtime
            // para aquela faixa); método/aridade ausente só é erro se NENHUMA versão o resolver — um
            // método introduzido por um upgrade de proxy continua válido.
            val perVersion = methods
                .groupBy { it.validFromBlock to it.validToBlock }
                .values
                .map { version ->
                    AbiMethodResolver.resolve(
                        version.map { ParsedAbiMethod(it.name, it.inputs, it.outputs) },
                        call.methodName,
                        call.args.size,
                    )
                }
            perVersion.filterIsInstance<MethodResolution.Ambiguous>().firstOrNull()?.let {
                throw InvalidEnrichmentProfileException(
                    "método '${call.methodName}' é ambíguo para ${call.args.size} argumento(s) na ABI de " +
                        "${call.targetContractAddress}: ${it.candidates.joinToString()} — informe a assinatura " +
                        "canônica (ex.: '${it.candidates.first()}')",
                )
            }
            val found = perVersion.filterIsInstance<MethodResolution.Found>().map { it.method }
            if (found.isEmpty()) {
                val arityMismatch = perVersion.filterIsInstance<MethodResolution.ArityMismatch>().firstOrNull()
                if (arityMismatch != null) {
                    throw InvalidEnrichmentProfileException(
                        "método '${call.methodName}' não tem sobrecarga com ${call.args.size} argumento(s) na ABI de " +
                            "${call.targetContractAddress} — disponíveis: ${arityMismatch.availableSignatures.joinToString()}",
                    )
                }
                throw InvalidEnrichmentProfileException(
                    "método '${call.methodName}' não encontrado (ou não é view/pure) na ABI de ${call.targetContractAddress}",
                )
            }
            val maxOutputs = found.maxOf { it.outputs.size }
            call.outputs.forEach { mapping ->
                if (mapping.outputIndex < 0 || mapping.outputIndex >= maxOutputs) {
                    throw InvalidEnrichmentProfileException(
                        "outputIndex ${mapping.outputIndex} fora da faixa de saídas do método '${call.methodName}' (0..${maxOutputs - 1})",
                    )
                }
            }
        }
    }
}
