package br.com.bbchain.evex.indexer.domain.contract

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult

/**
 * Porta de saída para persistência de contratos monitorados. Implementação JPA/Postgres desde E2
 * (`adapters/outbound/persistence/JpaMonitoredContractAdapter`). Update/patch de status não têm método
 * próprio nesta porta — `application` faz "load via findByAddress -> aplica mudança -> save()".
 */
interface MonitoredContractPort {
    fun save(contract: MonitoredContract): MonitoredContract

    fun findByAddress(address: String): MonitoredContract?

    fun findAll(network: Network?, page: Int, size: Int): PageResult<MonitoredContract>
}
