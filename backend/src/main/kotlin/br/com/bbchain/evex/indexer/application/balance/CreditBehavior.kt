package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehaviorStrategy
import br.com.bbchain.evex.indexer.domain.balance.BalanceDelta
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.readTokenId
import br.com.bbchain.evex.indexer.domain.balance.readWalletAddress
import br.com.bbchain.evex.indexer.domain.balance.toBalanceAmount
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import org.springframework.stereotype.Component

/** `BalanceBehavior.CREDIT` — sempre soma `valueParam` ao saldo do `walletParam` (ex.: `TokenMinted(recipient, amount)`). */
@Component
class CreditBehavior : BalanceBehaviorStrategy {
    override fun apply(config: BalanceMonitorConfig, event: BlockchainEvent): List<BalanceDelta> {
        val params = event.decodedParameters ?: return emptyList()
        val walletParam = config.walletParam ?: return emptyList()
        val wallet = params.readWalletAddress(walletParam) ?: return emptyList()
        val amount = params[config.valueParam]?.toBalanceAmount() ?: return emptyList()
        return listOf(
            BalanceDelta(
                walletAddress = wallet,
                contractAddress = event.contractAddress,
                network = event.network,
                tokenId = params.readTokenId(config.tokenIdParam),
                amount = amount,
            ),
        )
    }
}
