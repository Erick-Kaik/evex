package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiEntryCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.CatalogedAbiEntry
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import org.springframework.stereotype.Service

/**
 * Caso de uso fino (E24): catálogo **completo** do ABI de um contrato monitorado — funções de
 * qualquer `stateMutability`, eventos, erros customizados, constructor/fallback/receive —, derivado
 * das versões de ABI já cadastradas. Mirror **exato** de [ContractMethodCatalogService]/
 * [ContractEventCatalogService] (E19/DEC-082-083, E16/DEC-073/076) — mesma composição de portas,
 * mesma regra "contrato inexistente -> 404 / sem ABI -> lista vazia", só trocando [AbiEntryCatalogPort]
 * por [ContractMethodCatalogService]'s [br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort] e
 * `CatalogedMethod`/`CatalogedEvent` por [CatalogedAbiEntry].
 *
 * Um item de saída por (item de ABI × faixa de validade de ABI): `flatMap` das versões, cada item
 * parseado recebe o `validFromBlock`/`validToBlock` do `ContractAbi` de origem (cobre proxy
 * upgradável, DEC-007). O mesmo nome pode aparecer duplicado entre versões distintas — não é
 * deduplicado de propósito, é informação útil pro usuário ver o que mudou entre versões.
 */
@Service
class ContractAbiEntryCatalogService(
    private val contracts: MonitoredContractPort,
    private val contractAbis: ContractAbiPort,
    private val abiContentReader: AbiContentReaderPort,
    private val abiEntryCatalog: AbiEntryCatalogPort,
) {
    fun catalogFor(address: String): List<CatalogedAbiEntry> {
        val normalizedAddress = address.lowercase()
        if (contracts.findByAddress(normalizedAddress) == null) {
            throw ContractNotFoundException(normalizedAddress)
        }
        // Sem nenhuma versão de ABI -> lista vazia (200, estado "cadastre a ABI"), não 404.
        return contractAbis.findAllByContractAddress(normalizedAddress).flatMap { abi ->
            val json = abiContentReader.read(abi)
            abiEntryCatalog.entriesOf(json).map { parsed ->
                CatalogedAbiEntry(
                    kind = parsed.kind,
                    name = parsed.name,
                    stateMutability = parsed.stateMutability,
                    inputs = parsed.inputs,
                    outputs = parsed.outputs,
                    anonymous = parsed.anonymous,
                    validFromBlock = abi.validFromBlock,
                    validToBlock = abi.validToBlock,
                )
            }
        }
    }
}
