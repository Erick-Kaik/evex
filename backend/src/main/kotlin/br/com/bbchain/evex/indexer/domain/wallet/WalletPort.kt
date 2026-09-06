package br.com.bbchain.evex.indexer.domain.wallet

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult

/**
 * Porta de saída para persistência de carteiras cadastradas (E22). Espelha
 * [br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort] 1:1 — implementação JPA/Postgres
 * em `adapters/outbound/persistence/JpaWalletAdapter` (tabela `monitored_wallets`). Update/patch de
 * status não têm método próprio nesta porta — `application` faz "load via findByAddress -> aplica
 * mudança -> save()", igual contrato.
 */
interface WalletPort {
    fun save(wallet: Wallet): Wallet

    fun findByAddress(address: String): Wallet?

    fun findAll(network: Network?, page: Int, size: Int): PageResult<Wallet>

    /**
     * Projeção leve (só endereços, sem carregar [Wallet] inteiro) — usada pela descoberta automática
     * de carteiras (DEC-080, `GET /v1/wallets/suggestions`) pra excluir da sugestão os endereços já
     * cadastrados. **Não** é uma duplicação de [findAll]: `findAll` pagina e devolve entidades
     * completas pra UI de listagem; este método existe só pra alimentar uma exclusão em memória
     * (conjunto pequeno, uma instância do Evex por rede/cliente) sem o custo de montar `Wallet`
     * completos nem acoplar a consulta de saldo (`AssetBalancePort`, em outro módulo) a esta tabela.
     */
    fun findAllAddresses(network: Network?): Set<String>
}
