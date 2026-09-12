package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.UupsDetectionResult
import br.com.bbchain.evex.indexer.domain.abi.UupsDetector
import org.springframework.stereotype.Service

/**
 * Caso de uso fino (E28): detecção de proxy upgradável UUPS/ERC-1822 a partir do ABI **atualmente
 * vigente** de um contrato monitorado — `GET /v1/contracts/{address}/upgradeability`.
 *
 * Reaproveita [ContractAbiEntryCatalogService.catalogFor] (E24) pra obter o catálogo completo (mesma
 * regra de 404 quando o contrato não está cadastrado — propagada sem tratamento especial, já é
 * [br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException]) e filtra pra só os itens da
 * faixa de blocos vigente (`validToBlock == null`) — um contrato proxy upgradável (DEC-007) pode ter
 * tido uma versão antiga que declarava `proxiableUUID()`/métodos de upgrade e a vigente não (ou
 * vice-versa); só a vigente importa aqui, é sobre o estado *atual* do contrato, não seu histórico.
 * Contrato sem nenhuma versão de ABI cadastrada -> lista vazia -> [UupsDetector] devolve
 * `isUpgradeable = false`, não erro (mesma regra dos demais catálogos derivados de ABI).
 */
@Service
class UupsDetectionService(
    private val contractAbiEntryCatalogService: ContractAbiEntryCatalogService,
) {
    fun detect(address: String): UupsDetectionResult {
        val currentAbiEntries = contractAbiEntryCatalogService.catalogFor(address)
            .filter { it.validToBlock == null }
        return UupsDetector.detect(currentAbiEntries)
    }
}
