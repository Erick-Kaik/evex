package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.PageResult
import java.util.UUID

/**
 * Porta de saída para persistência/consulta de configurações de webhook. Implementação JPA/Postgres em
 * `adapters/outbound/persistence/JpaWebhookConfigAdapter`.
 */
interface WebhookConfigPort {
    fun save(config: WebhookConfig): WebhookConfig

    fun findById(id: UUID): WebhookConfig?

    /**
     * Configs **ativas** pro par `(contractAddress, eventName)` — consultada pelo
     * `WebhookMatchingService` a cada evento decodificado. Lista, não `?`: nada impede >1 webhook
     * ativo pro mesmo par.
     */
    fun findActive(contractAddress: String, eventName: String): List<WebhookConfig>

    fun findAll(page: Int, size: Int): PageResult<WebhookConfig>
}
