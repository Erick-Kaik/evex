package br.com.bbchain.evex.indexer.application.contract

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.LineageNetworkMismatchException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/** Caso de uso: cadastro, consulta e atualização de contratos monitorados. Não conhece HTTP nem persistência concreta. */
@Service
class MonitoredContractService(
    private val contracts: MonitoredContractPort,
) {
    /**
     * `predecessorAddress` (E29/DEC-096/DEC-097, opcional) marca este cadastro como o redeploy de um
     * contrato já monitorado — a linhagem inteira compartilha um `lineageId`. Se o predecessor ainda
     * não tinha `lineageId` (caso comum: feature nova, contratos antigos ficam `null`), ele é "batizado"
     * agora (`lineageId = seu próprio id`) — é nesse instante que a linhagem nasce. Sem
     * `predecessorAddress`, o contrato novo recebe `lineageId = seu próprio id` (toda linhagem começa
     * trivialmente com tamanho 1).
     */
    fun register(
        address: String,
        name: String,
        network: Network,
        description: String?,
        version: String?,
        predecessorAddress: String? = null,
    ): MonitoredContract {
        val normalizedAddress = address.lowercase()
        if (contracts.findByAddress(normalizedAddress) != null) {
            throw ContractAlreadyExistsException(normalizedAddress)
        }
        val newId = UUID.randomUUID()
        val lineageId = predecessorAddress?.let { resolveLineageId(normalizedAddress, network, it) } ?: newId
        val contract = MonitoredContract(
            id = newId,
            address = normalizedAddress,
            name = name,
            description = description,
            version = version,
            network = network,
            status = ContractStatus.ACTIVE,
            lineageId = lineageId,
            createdAt = Instant.now(),
        )
        return contracts.save(contract)
    }

    /** Resolve (e, se necessário, batiza) o `lineageId` a herdar de `predecessorAddress`. */
    private fun resolveLineageId(newAddress: String, network: Network, predecessorAddress: String): UUID {
        val normalizedPredecessorAddress = predecessorAddress.lowercase()
        val predecessor = contracts.findByAddress(normalizedPredecessorAddress)
            ?: throw ContractNotFoundException(normalizedPredecessorAddress)
        if (predecessor.network != network) {
            throw LineageNetworkMismatchException(address = newAddress, predecessorAddress = normalizedPredecessorAddress)
        }
        val lineageId = predecessor.lineageId ?: predecessor.id
        if (predecessor.lineageId == null) {
            contracts.save(predecessor.copy(lineageId = lineageId))
        }
        return lineageId
    }

    fun getByAddress(address: String): MonitoredContract =
        contracts.findByAddress(address.lowercase()) ?: throw ContractNotFoundException(address)

    fun list(network: Network?, page: Int, size: Int): PageResult<MonitoredContract> =
        contracts.findAll(network, page, size)

    /**
     * Linhagem inteira de um contrato (E29/DEC-096/DEC-097), ordenada por `createdAt`, inclusive ele
     * mesmo. Sem `lineageId` (contrato pré-existente à feature, ou nunca envolvido num redeploy),
     * devolve só o próprio contrato — uma linhagem de tamanho 1 sem precisar consultar o banco de novo.
     */
    fun getLineage(address: String): List<MonitoredContract> {
        val contract = getByAddress(address)
        val lineageId = contract.lineageId ?: return listOf(contract)
        return contracts.findAllByLineageId(lineageId)
    }

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
