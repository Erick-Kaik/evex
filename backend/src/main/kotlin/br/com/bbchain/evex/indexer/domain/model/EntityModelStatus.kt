package br.com.bbchain.evex.indexer.domain.model

/**
 * Situação de um [EntityModel]. Enum próprio deste domínio (DEC-080 item 1 — mesmo racional de
 * [br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus]: não reaproveita
 * `ContractStatus`/`WalletStatus`). Só 2 valores, mesma razão de `EnrichmentProfileStatus` — sem
 * noção de "pausado", só "disponível pra novos cadastros" vs. "arquivado".
 */
enum class EntityModelStatus {
    ACTIVE,
    ARCHIVED,
}
