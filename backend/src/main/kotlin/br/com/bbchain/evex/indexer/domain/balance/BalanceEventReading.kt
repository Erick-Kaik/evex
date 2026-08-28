package br.com.bbchain.evex.indexer.domain.balance

/** Endereço zero — sentinela de mint/burn, só tem significado dentro de `application/balance/TransferBehavior`. */
const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

/**
 * Lê um parâmetro de endereço de `BlockchainEvent.decodedParameters` — `null` se [param] não existe no
 * mapa ou o valor não é uma `String` (config apontando pra um nome de parâmetro que o evento não tem;
 * ver KDoc de [BalanceBehaviorStrategy] sobre nunca lançar exceção por config mal formada).
 */
fun Map<String, Any?>.readWalletAddress(param: String): String? = (this[param] as? String)?.lowercase()

/** Lê o parâmetro opcional de `tokenId` — `null` se [param] for `null` (não aplicável, ex.: ERC20) ou ausente no evento. */
fun Map<String, Any?>.readTokenId(param: String?): String? = param?.let { key -> this[key]?.toString() }
