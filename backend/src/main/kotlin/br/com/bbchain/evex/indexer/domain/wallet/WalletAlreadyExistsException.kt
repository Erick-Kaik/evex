package br.com.bbchain.evex.indexer.domain.wallet

/** Já existe uma carteira cadastrada com este endereço (chave natural, imutável após criação). */
class WalletAlreadyExistsException(val address: String) :
    RuntimeException("Carteira já cadastrada: $address")
