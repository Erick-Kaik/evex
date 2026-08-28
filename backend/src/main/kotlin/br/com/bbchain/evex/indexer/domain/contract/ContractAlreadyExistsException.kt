package br.com.bbchain.evex.indexer.domain.contract

/** Já existe um contrato monitorado cadastrado com este endereço (chave natural, imutável após criação). */
class ContractAlreadyExistsException(val address: String) :
    RuntimeException("Contrato já cadastrado: $address")
