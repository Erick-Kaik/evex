package br.com.bbchain.evex.indexer.application.contract

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import java.util.UUID
import org.springframework.stereotype.Service

/** Caso de uso: cadastro, consulta e atualização de contratos monitorados. Não conhece HTTP nem persistência concreta. */
@Service
class MonitoredContractService(
    private val contracts: MonitoredContractPort,
) {
    fun register(address: String, name: String, network: Network, description: String?, version: String?): MonitoredContract {
        val normalizedAddress = address.lowercase()
        if (contracts.findByAddress(normalizedAddress) != null) {
            throw ContractAlreadyExistsException(normalizedAddress)
        }
        val contract = MonitoredContract(
            id = UUID.randomUUID(),
            address = normalizedAddress,
            name = name,
            description = description,
            version = version,
            network = network,
            status = ContractStatus.ACTIVE,
        )
        return contracts.save(contract)
    }

    fun getByAddress(address: String): MonitoredContract =
        contracts.findByAddress(address.lowercase()) ?: throw ContractNotFoundException(address)

    fun list(network: Network?, page: Int, size: Int): PageResult<MonitoredContract> =
        contracts.findAll(network, page, size)

    /** `address` e `network` são imutáveis (chave natural) — só `name`/`description`/`version` mudam. */
    fun update(address: String, name: String, description: String?, version: String?): MonitoredContract {
        val existing = getByAddress(address)
        val updated = existing.copy(name = name, description = description, version = version)
        return contracts.save(updated)
    }

    /** Transição de status é livre (sem máquina de estados) — decisão travada com o usuário. */
    fun updateStatus(address: String, status: ContractStatus): MonitoredContract {
        val existing = getByAddress(address)
        val updated = existing.copy(status = status)
        return contracts.save(updated)
    }
}
