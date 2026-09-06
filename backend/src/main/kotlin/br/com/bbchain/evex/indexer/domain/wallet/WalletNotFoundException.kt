package br.com.bbchain.evex.indexer.domain.wallet

/** Nenhuma carteira cadastrada com este endereço. */
class WalletNotFoundException(val address: String) : RuntimeException("Carteira não encontrada: $address")
