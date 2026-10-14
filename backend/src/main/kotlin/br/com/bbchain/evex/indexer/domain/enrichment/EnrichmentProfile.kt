package br.com.bbchain.evex.indexer.domain.enrichment

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Perfil de enriquecimento (E19/DEC-082) — lista **ordenada e reaproveitável** de chamadas de leitura
 * on-chain (`eth_call`), cadastrada como recurso próprio (simétrico a Contrato/Carteira), referenciada
 * por 0+ [br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig] via
 * `enrichmentProfileIds: List<UUID>`.
 *
 * **Referência viva** (decisão explícita do usuário, ver roadmap E19): a resolução no dispatch busca o
 * conteúdo do perfil **na hora** (por [id], nunca copiado/snapshotado) — editar um perfil depois muda
 * imediatamente todos os webhooks que o referenciam. Efeito colateral aceito: um erro de edição num
 * perfil compartilhado propaga pra todos os webhooks que o usam ao mesmo tempo.
 *
 * `network` (regra "campo `network` em toda entidade", `CLAUDE.md` raiz) — como uma instância do Evex
 * é sempre de uma única rede, este campo hoje é sempre igual ao da instância; existe pra manter a
 * simetria com o resto do domínio e permitir filtro em `findAll` (mirror de
 * [br.com.bbchain.evex.indexer.domain.wallet.WalletPort.findAll]).
 *
 * Sem exclusão real — só arquivamento ([EnrichmentProfileStatus.ARCHIVED], mesma regra do resto da
 * API). **Decisão (E19/DEC-082)**: um perfil arquivado continua resolvível se referenciado por um
 * webhook ativo — arquivar não é "apagar", é só um sinal de "não oferecer mais pra novos cadastros" na
 * UI (mesmo princípio de `ContractStatus.ARCHIVED`/`WalletStatus.ARCHIVED`, que também não bloqueiam
 * leitura). Bloquear a resolução exigiria o dispatch falhar delivery já enfileirada por causa de uma
 * decisão de arquivamento tomada depois — pior experiência do que simplesmente continuar funcionando
 * até o usuário desreferenciar o perfil do webhook.
 */
data class EnrichmentProfile(
    val id: UUID,
    val name: String,
    val description: String?,
    val network: Network,
    val status: EnrichmentProfileStatus,
    val calls: List<EnrichmentCall>,
    val createdAt: Instant,
)

/**
 * Uma chamada de leitura dentro de um [EnrichmentProfile], resolvida em ordem dentro da lista
 * `EnrichmentProfile.calls` (permite encadeamento: uma chamada pode referenciar `{{enriched.x}}` de uma
 * chamada anterior do mesmo perfil, ou de um perfil anterior na lista `WebhookConfig.enrichmentProfileIds`).
 *
 * [targetContractAddress] **não precisa ser** o contrato do evento que disparou o webhook — pode ser
 * qualquer outro contrato monitorado (decisão do usuário, roadmap E19: "o usuário poderia mirar
 * contratos que já cadastrou/conhece no Evex"). A ABI de leitura é resolvida pelo mesmo cadastro já
 * existente daquele contrato ([br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort]/
 * [br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort]/`AbiCache`), nunca copiada aqui.
 */
data class EnrichmentCall(
    val targetContractAddress: String,
    val methodName: String,
    /**
     * Um argumento por posição de entrada do método, na ordem declarada no Solidity. Cada item é
     * **literal**, `{{decodedParameters.x}}` (campo do evento que disparou o webhook) ou
     * `{{enriched.y}}` (saída de uma chamada anterior já resolvida na sequência) — mesma sintaxe de
     * interpolação de [br.com.bbchain.evex.indexer.application.webhook.WebhookPayloadRenderer].
     */
    val args: List<String>,
    /** 1 por saída do método que o usuário quer expor no payload — nem toda saída precisa ser mapeada. */
    val outputs: List<EnrichmentOutputMapping>,
)

/**
 * Mapeia 1 saída de retorno de uma [EnrichmentCall] pra 1 token `{{enriched.<placeholder>}}` no
 * `payloadTemplate` do webhook. [outputIndex] é a posição na tupla de retorno do método (não o nome —
 * nomes de saída do Solidity são só dica de UI, ver
 * [br.com.bbchain.evex.indexer.domain.abi.CatalogedMethodOutput]).
 */
data class EnrichmentOutputMapping(
    val outputIndex: Int,
    val placeholder: String,
)
