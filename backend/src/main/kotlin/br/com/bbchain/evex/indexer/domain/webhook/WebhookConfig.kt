package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Configuração de webhook cadastrada pelo usuário (doc de produto §4.6) — associa um
 * `(contractAddress, eventName)` a uma [url] de destino, [filters] opcionais (`emptyList()` = sem
 * filtro, sempre dispara) e um [payloadTemplate] opcional (decisão tomada com o usuário: string JSON
 * com placeholders `{{campo}}`/`{{decodedParameters.nome}}`, interpolação simples — se ausente, usa o
 * envelope JSON padrão de [br.com.bbchain.evex.indexer.adapters.outbound.webhook.WebhookPayloadRenderer]).
 *
 * "Por usuário" não implica uma entidade `User` real — PoC sem auth (mesmo padrão sem "dono" já usado
 * em `BalanceMonitorConfig`, E7).
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
)
