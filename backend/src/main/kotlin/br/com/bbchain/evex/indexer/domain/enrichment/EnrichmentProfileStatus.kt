package br.com.bbchain.evex.indexer.domain.enrichment

/**
 * Situação de um [EnrichmentProfile]. Enum próprio deste domínio (DEC-080 item 1: cada agregado é dono
 * do próprio vocabulário — não reaproveita `ContractStatus`/`WalletStatus` mesmo com sobreposição de
 * nomes). Só 2 valores (sem `PAUSED`): um perfil de enriquecimento não tem noção de "monitoramento
 * ativo/pausado" como contrato/carteira — ele só existe pra ser referenciado sob demanda no dispatch de
 * um webhook; a única transição de estado que faz sentido é "disponível pra novos cadastros" vs.
 * "arquivado" (mesma regra "sem exclusão real" do resto da API).
 */
enum class EnrichmentProfileStatus {
    ACTIVE,
    ARCHIVED,
}
