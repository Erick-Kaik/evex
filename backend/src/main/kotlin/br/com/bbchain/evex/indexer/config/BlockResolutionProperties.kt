package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** `indexer.blocks.*` — conversão data -> bloco ([br.com.bbchain.evex.indexer.application.block.BlockResolutionService]). */
@Component
@ConfigurationProperties(prefix = "indexer.blocks")
class BlockResolutionProperties {
    /** Teto de chamadas RPC (`eth_blockNumber` + `eth_getBlockByNumber`) por resolução. */
    var maxRpcCalls: Int = 32
}
