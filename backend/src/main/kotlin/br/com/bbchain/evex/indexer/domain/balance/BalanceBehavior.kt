package br.com.bbchain.evex.indexer.domain.balance

/**
 * Como um evento decodificado afeta o saldo de uma carteira (doc de produto §4.4,
 * `abi-strategy-design.md` §2). Resolvido pelo [BalanceBehaviorStrategy] correspondente em
 * `application/balance/`.
 */
enum class BalanceBehavior {
    /** Sempre soma ao saldo do `walletParam` — ex.: `TokenMinted(recipient, amount)`. */
    CREDIT,

    /** Sempre subtrai do saldo do `walletParam` — ex.: `TokenBurned(holder, amount)`. */
    DEBIT,

    /** Deduz de `fromParam`, credita em `toParam` — ex.: `Transfer(from, to, value)`. */
    TRANSFER,

    /** `valueParam` já é o delta assinado (`int256`) aplicado direto no `walletParam` — ex.: `BalanceUpdated(wallet, int256 delta)`. */
    DELTA,
}
