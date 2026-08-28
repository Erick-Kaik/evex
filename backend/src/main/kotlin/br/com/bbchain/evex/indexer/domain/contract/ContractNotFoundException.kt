package br.com.bbchain.evex.indexer.domain.contract

/** Nenhum contrato monitorado cadastrado com este endereço. */
class ContractNotFoundException(val address: String) : RuntimeException("Contrato não encontrado: $address")
