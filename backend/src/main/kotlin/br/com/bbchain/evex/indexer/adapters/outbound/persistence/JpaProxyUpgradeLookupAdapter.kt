package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.discovery.ProxyUpgradeLookupPort
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedEvent
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeRecord
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedLog
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Implementação JPA/Postgres de [ProxyUpgradeLookupPort] (DEC-104). Reaproveita
 * [BlockchainEventJpaRepository] (mesma tabela, sem entidade nova). O parse do `topic1` tem um só
 * dono: [UpgradedEvent.addressFromTopic] — linhas com `topic1` que não é endereço são ignoradas.
 *
 * Particiona a lista de entrada em blocos de [CHUNK] endereços para ficar longe do limite de 32767
 * parâmetros do protocolo Postgres (a query do passo 1 usa 2 listas); na prática o Blockscout pagina
 * em ~50, então é 1 query por passo.
 */
@Component
class JpaProxyUpgradeLookupAdapter(
    private val repository: BlockchainEventJpaRepository,
) : ProxyUpgradeLookupPort {

    @Transactional(readOnly = true)
    override fun findUpgradedLogsInvolving(addresses: Set<String>): List<UpgradedLog> {
        if (addresses.isEmpty()) return emptyList()
        return addresses.map { it.lowercase() }.distinct().chunked(CHUNK)
            .flatMap { chunk ->
                repository.findUpgradedInvolving(chunk, chunk.map(UpgradedEvent::topicFor))
            }
            .mapNotNull(::toLog)
            .distinct()
    }

    @Transactional(readOnly = true)
    override fun findLatestUpgradedByProxy(proxyAddresses: Set<String>): List<UpgradedLog> {
        if (proxyAddresses.isEmpty()) return emptyList()
        return proxyAddresses.map { it.lowercase() }.distinct().chunked(CHUNK)
            .flatMap { repository.findLatestUpgradedByProxy(it) }
            .mapNotNull(::toLog)
    }

    @Transactional(readOnly = true)
    override fun findUpgradeHistory(proxy: String): List<UpgradeRecord> =
        repository.findUpgradeHistoryByProxy(proxy.lowercase()).mapNotNull { row ->
            val implementation = UpgradedEvent.addressFromTopic(row[1] as String?) ?: return@mapNotNull null
            UpgradeRecord(
                proxyAddress = (row[0] as String).lowercase(),
                implementationAddress = implementation,
                blockNumber = (row[2] as Number).toLong(),
                logIndex = (row[3] as Number).toLong(),
                txHash = row[4] as String,
            )
        }

    private fun toLog(row: Array<Any?>): UpgradedLog? {
        val implementation = UpgradedEvent.addressFromTopic(row[1] as String?) ?: return null
        return UpgradedLog(
            proxyAddress = (row[0] as String).lowercase(),
            implementationAddress = implementation,
            blockNumber = (row[2] as Number).toLong(),
            logIndex = (row[3] as Number).toLong(),
        )
    }

    private companion object {
        const val CHUNK = 10_000
    }
}
