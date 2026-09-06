package br.com.bbchain.evex.indexer.domain.wallet

/**
 * Situação operacional de uma carteira cadastrada. Transição livre entre estados (sem máquina de
 * estados) — mesma regra de [br.com.bbchain.evex.indexer.domain.contract.ContractStatus].
 *
 * Enum **distinto** de `ContractStatus`, não reaproveitado (DEC-080): mesmos 3 valores, mas cada
 * domínio é dono do próprio vocabulário — convenção já seguida pelo resto do projeto (ex.:
 * `AbiVersionOrderException`/`ContractNotFoundException` não são compartilhadas entre domínios mesmo
 * quando a forma é parecida). Reaproveitar `ContractStatus` aqui criaria um acoplamento entre os
 * domínios `wallet`/`contract` só porque os valores coincidem hoje — se um dos dois ganhar um 4º
 * estado específico no futuro (ex.: `SUSPECTED_CONTRACT` só em carteira), o enum compartilhado
 * quebraria a coesão do outro domínio.
 */
enum class WalletStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED,
}
