package br.com.bbchain.evex.indexer.domain.balance

/** Combinação de campos inválida para o [BalanceBehavior] informado (ver KDoc de [BalanceMonitorConfig]). */
class InvalidBalanceMonitorConfigException(message: String) : RuntimeException(message)
