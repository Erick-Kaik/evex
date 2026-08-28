package br.com.bbchain.evex.indexer.domain.abi

/**
 * Uma interface padrão built-in (ERC20/ERC721/ERC1155 — doc de produto §4.5) usada pela detecção
 * automática. `eventTopic0s` é universal (`keccak256` da assinatura do evento não depende de `indexed`
 * nem de quem implementa) — pré-computado 1x no boot a partir de [builtinAbi], nunca recalculado por
 * requisição. Tipo de dado puro, sem I/O (mesmo espírito de
 * [br.com.bbchain.evex.indexer.domain.blockchain.PollWindow]) — quem monta as instâncias concretas
 * (parseando `builtinAbi` via `AbiParser`) é `adapters/outbound/abi/BuiltinInterfaceTemplates`.
 */
data class InterfaceTemplate(
    val name: String,
    val eventTopic0s: Set<String>,
    val builtinAbi: String,
)
