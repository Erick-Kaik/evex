package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Uma versão de ABI já resolvida em memória: faixa de validade + os [EventDescriptor] já parseados
 * (prontos pra decodificar, sem I/O). `abiId` existe só pra permitir localizar/atualizar esta entrada
 * quando [AbiRegistrationService][br.com.bbchain.evex.indexer.application.abi.AbiRegistrationService]
 * fecha a versão anterior (troca só `validToBlock`, não precisa reparsear).
 */
data class CachedAbiVersion(
    val abiId: UUID,
    val validFromBlock: Long,
    val validToBlock: Long?,
    val eventDescriptors: List<EventDescriptor>,
)

/**
 * Cache em memória de ABI por `contract_address`, resolvendo a versão vigente **por bloco** (DEC-007
 * — não é `Map<address, Abi>` simples, é `Map<address, List<CachedAbiVersion>>` ordenada por
 * `validFromBlock`). É o único lugar de onde [AbiDecodingAdapter] lê — nunca bate em banco/disco no
 * caminho crítico de decodificação (regra não-negociável do projeto).
 *
 * Populado de duas formas: (1) warm-up no boot (`@EventListener(ApplicationReadyEvent::class)`),
 * lendo todas as versões via [ContractAbiPort.findAll] + resolvendo conteúdo via
 * [AbiStorageResolver]/[AbiParser]; (2) incrementalmente por
 * [AbiRegistrationService][br.com.bbchain.evex.indexer.application.abi.AbiRegistrationService] a cada
 * novo cadastro, reaproveitando os [EventDescriptor] já parseados na hora do registro (sem reler).
 */
@Component
class AbiCache(
    private val contractAbis: ContractAbiPort,
    private val storageResolver: AbiStorageResolver,
    private val abiParser: AbiParser,
) {
    private val logger = LoggerFactory.getLogger(AbiCache::class.java)
    private val cache = ConcurrentHashMap<String, List<CachedAbiVersion>>()

    fun versionsFor(address: String): List<CachedAbiVersion> = cache[address.lowercase()].orEmpty()

    fun put(address: String, versions: List<CachedAbiVersion>) {
        cache[address.lowercase()] = versions.sortedBy { it.validFromBlock }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        val grouped = contractAbis.findAll().groupBy { it.contractAddress.lowercase() }
        var loaded = 0
        grouped.forEach { (address, abis) ->
            val versions = abis.mapNotNull { abi ->
                try {
                    val storage = storageResolver.resolve(abi.storageType)
                    val content = storage.retrieve(abi.storageLocation)
                    val descriptors = abiParser.parse(content)
                    CachedAbiVersion(
                        abiId = abi.id,
                        validFromBlock = abi.validFromBlock,
                        validToBlock = abi.validToBlock,
                        eventDescriptors = descriptors,
                    )
                } catch (ex: Exception) {
                    // Não derruba o boot por causa de 1 ABI corrompida/inacessível — loga e segue sem
                    // ela; o endereço fica sem decodificação até ser recadastrado.
                    logger.warn("Falha ao carregar ABI {} de {} no warm-up do cache: {}", abi.id, address, ex.message)
                    null
                }
            }
            if (versions.isNotEmpty()) {
                cache[address] = versions.sortedBy { it.validFromBlock }
                loaded += versions.size
            }
        }
        logger.info("AbiCache: {} versões de ABI carregadas para {} contratos no warm-up.", loaded, grouped.size)
    }
}
