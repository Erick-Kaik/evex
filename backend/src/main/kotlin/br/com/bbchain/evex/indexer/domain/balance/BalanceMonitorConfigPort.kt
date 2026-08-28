package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.util.UUID

/**
 * Porta de saída para persistência/consulta de configurações de monitoramento de saldo.
 * Implementação JPA/Postgres em `adapters/outbound/persistence/JpaBalanceMonitorConfigAdapter`.
 */
interface BalanceMonitorConfigPort {
    fun save(config: BalanceMonitorConfig): BalanceMonitorConfig

    fun findById(id: UUID): BalanceMonitorConfig?

    /**
     * Configs **ativas** pro par `(contractAddress, eventName)` — consultada pelo
     * `BalanceProjectionService` a cada evento decodificado. Lista, não `?`: nada impede >1 config
     * ativa pro mesmo par na PoC, mesmo que o caso comum seja 0 ou 1.
     */
    fun findActive(contractAddress: String, eventName: String): List<BalanceMonitorConfig>

    fun findAll(page: Int, size: Int): PageResult<BalanceMonitorConfig>
}
