package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethod
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import org.springframework.stereotype.Service

/**
 * Caso de uso fino (E19/DEC-082): catálogo de métodos de leitura (`view`/`pure`) de um contrato
 * monitorado, derivado das versões de ABI já cadastradas. Mirror **exato** de
 * [ContractEventCatalogService] (E16/DEC-073/076) — mesma composição de portas, mesma regra
 * "contrato inexistente -> 404 / sem ABI -> lista vazia", só trocando [br.com.bbchain.evex.indexer.
 * domain.abi.AbiEventCatalogPort] por [AbiMethodCatalogPort] e `CatalogedEvent` por [CatalogedMethod].
 *
 * Stub de estrutura (E19/DEC-082, passo de design do `hexagonal-architect`) — corpo a implementar por
 * `kotlin-backend-dev` copiando a implementação de [ContractEventCatalogService] linha a linha
 * (troca mecânica de tipo, sem lógica nova).
 */
@Service
class ContractMethodCatalogService(
    private val contracts: MonitoredContractPort,
    private val contractAbis: ContractAbiPort,
    private val abiContentReader: AbiContentReaderPort,
    private val abiMethodCatalog: AbiMethodCatalogPort,
) {
    fun catalogFor(address: String): List<CatalogedMethod> {
        val normalizedAddress = address.lowercase()
        if (contracts.findByAddress(normalizedAddress) == null) {
            throw ContractNotFoundException(normalizedAddress)
        }
        // Sem nenhuma versão de ABI -> lista vazia (200, estado "cadastre a ABI"), não 404.
        return contractAbis.findAllByContractAddress(normalizedAddress).flatMap { abi ->
            val json = abiContentReader.read(abi)
            abiMethodCatalog.methodsOf(json).map { parsed ->
                CatalogedMethod(
                    name = parsed.name,
                    inputs = parsed.inputs,
                    outputs = parsed.outputs,
                    validFromBlock = abi.validFromBlock,
                    validToBlock = abi.validToBlock,
                )
            }
        }
    }
}
