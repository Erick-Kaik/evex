package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Configuração de webhook cadastrada pelo usuário (doc de produto §4.6) — associa um
 * `(contractAddress, eventName)` a uma [url] de destino, [filters] opcionais (`emptyList()` = sem
 * filtro, sempre dispara) e um [payloadTemplate] opcional (decisão tomada com o usuário: string JSON
 * com placeholders `{{campo}}`/`{{decodedParameters.nome}}`, interpolação simples — se ausente, usa o
 * envelope JSON padrão de [br.com.bbchain.evex.indexer.application.webhook.WebhookPayloadRenderer]).
 *
 * "Por usuário" não implica uma entidade `User` real — PoC sem auth (mesmo padrão sem "dono" já usado
 * em `BalanceMonitorConfig`, E7).
 *
 * [enrichmentProfileIds]/[entityModelIds] (E19/DEC-082) — referências **ordenadas** e **vivas** (nunca
 * copiadas) a
 * [br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile]/[br.com.bbchain.evex.indexer.
 * domain.model.EntityModel] cadastrados à parte; `emptyList()` (default) preserva 100% o comportamento
 * anterior à E19 — regressão zero pra quem não usa a feature. Validação de existência (contrato-
 * alvo/método do perfil, contribuidor/campo do modelo) acontece no cadastro **daqueles** recursos, não
 * aqui — o `WebhookConfigService` só valida que os `id`s referenciados existem (a implementar,
 * `kotlin-backend-dev`).
 */
data class WebhookConfig(
    val id: UUID,
    val contractAddress: String,
    val network: Network,
    val eventName: String,
    val url: String,
    val filters: List<WebhookFilterCondition>,
    val payloadTemplate: String?,
    val active: Boolean,
    val createdAt: Instant,
    val enrichmentProfileIds: List<UUID> = emptyList(),
    val entityModelIds: List<UUID> = emptyList(),
)
