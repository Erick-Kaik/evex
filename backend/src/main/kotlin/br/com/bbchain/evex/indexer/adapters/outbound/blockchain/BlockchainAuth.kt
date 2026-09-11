package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties

/**
 * Header de autenticação opcional do RPC (E27/DEC-094) — função pura, sem dependência de HTTP/WebSocket,
 * reaproveitada tanto por [Web3jBlockchainClient] (`HttpService.addHeaders`) quanto por
 * [WebSocketPollTrigger] (`WebSocketClient(URI, Map)`, handshake). Regra: token presente **e**
 * `authEnabled != false` — não é um OR puro (com `authEnabled` default `true`, um OR faria
 * `authEnabled=true`+token vazio mandar `Authorization: Bearer ` quebrado). `authToken` vazio (default)
 * preserva 100% o comportamento sem auth de hoje — nenhuma rede sem auth (Hardhat local, `local-env`,
 * testes) é afetada.
 */
internal fun BlockchainProperties.authHeaders(): Map<String, String> =
    if (authEnabled && authToken.isNotBlank()) mapOf("Authorization" to "Bearer $authToken") else emptyMap()
