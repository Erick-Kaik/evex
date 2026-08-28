package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehaviorStrategy
import br.com.bbchain.evex.indexer.domain.balance.BalanceDelta
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.ZERO_ADDRESS
import br.com.bbchain.evex.indexer.domain.balance.readTokenId
import br.com.bbchain.evex.indexer.domain.balance.readWalletAddress
import br.com.bbchain.evex.indexer.domain.balance.toBalanceAmount
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import org.springframework.stereotype.Component

/**
 * `BalanceBehavior.TRANSFER` — deduz `valueParam` de `fromParam`, credita em `toParam` (ex.:
 * `Transfer(from, to, value)`). O endereço zero (`ZERO_ADDRESS`, sentinela de mint/burn) é pulado dos
 * dois lados — só faz sentido aqui, `CreditBehavior`/`DebitBehavior` já são unidirecionais por
 * natureza (`abi-strategy-design.md` §2).
 */
@Component
class TransferBehavior : BalanceBehaviorStrategy {
    override fun apply(config: BalanceMonitorConfig, event: BlockchainEvent): List<BalanceDelta> {
        val params = event.decodedParameters ?: return emptyList()
        val fromParam = config.fromParam ?: return emptyList()
        val toParam = config.toParam ?: return emptyList()
        val from = params.readWalletAddress(fromParam) ?: return emptyList()
        val to = params.readWalletAddress(toParam) ?: return emptyList()
        val amount = params[config.valueParam]?.toBalanceAmount() ?: return emptyList()
        val tokenId = params.readTokenId(config.tokenIdParam)

        val deltas = mutableListOf<BalanceDelta>()
        if (from != ZERO_ADDRESS) {
            deltas += BalanceDelta(walletAddress = from, contractAddress = event.contractAddress, network = event.network, tokenId = tokenId, amount = amount.negate())
        }
        if (to != ZERO_ADDRESS) {
            deltas += BalanceDelta(walletAddress = to, contractAddress = event.contractAddress, network = event.network, tokenId = tokenId, amount = amount)
        }
        return deltas
    }
}
