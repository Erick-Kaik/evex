package br.com.bbchain.evex.indexer.domain.contract

/** Situação operacional de um contrato monitorado. Transição livre entre estados (sem máquina de estados). */
enum class ContractStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED,
}
