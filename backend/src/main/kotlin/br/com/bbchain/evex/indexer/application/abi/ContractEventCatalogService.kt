package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiEventCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.CatalogedEvent
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import org.springframework.stereotype.Service

/**
 * Caso de uso fino (E16 / DEC-073 / DEC-076): catálogo de eventos decodificáveis de um contrato
 * monitorado, derivado das versões de ABI já cadastradas — não consulta a rede, não cria tabela nova.
 *
 * Contrato inexistente -> [ContractNotFoundException] (404). Contrato sem nenhuma ABI -> lista vazia
 * (200, **não** é erro — é o estado "cadastre a ABI").
 *
 * Só depende de portas de domínio:
 * - [MonitoredContractPort] — checagem de existência (404);
 * - [ContractAbiPort] — as versões de ABI (com faixa de blocos) do endereço;
 * - [AbiContentReaderPort] — o JSON bruto de cada versão (resolve storage internamente);
 * - [AbiEventCatalogPort] — parse do JSON -> eventos+inputs, sem Web3j vazando.
 *
 * Um item de saída por (evento × faixa de validade de ABI): `flatMap` das versões, cada evento
 * parseado recebe o `validFromBlock`/`validToBlock` do [ContractAbi] de origem (cobre proxy
 * upgradável, DEC-007 — mesmo evento pode sair 2×).
 */
@Service
class ContractEventCatalogService(
    private val contracts: MonitoredContractPort,
    private val contractAbis: ContractAbiPort,
    private val abiContentReader: AbiContentReaderPort,
    private val abiEventCatalog: AbiEventCatalogPort,
) {
    fun catalogFor(address: String): List<CatalogedEvent> {
        val normalizedAddress = address.lowercase()
        if (contracts.findByAddress(normalizedAddress) == null) {
            throw ContractNotFoundException(normalizedAddress)
        }
        // Sem nenhuma versão de ABI -> lista vazia (200, estado "cadastre a ABI"), não 404.
        return contractAbis.findAllByContractAddress(normalizedAddress).flatMap { abi ->
            val json = abiContentReader.read(abi)
            abiEventCatalog.eventsOf(json).map { parsed ->
                CatalogedEvent(
                    name = parsed.name,
                    inputs = parsed.inputs,
                    validFromBlock = abi.validFromBlock,
                    validToBlock = abi.validToBlock,
                )
            }
        }
    }
}
