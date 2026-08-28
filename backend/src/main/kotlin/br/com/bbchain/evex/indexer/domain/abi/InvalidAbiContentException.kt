package br.com.bbchain.evex.indexer.domain.abi

/** ABI JSON malformado, ou usando um schema/tipo Solidity não suportado pelo parser dinâmico. */
class InvalidAbiContentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
