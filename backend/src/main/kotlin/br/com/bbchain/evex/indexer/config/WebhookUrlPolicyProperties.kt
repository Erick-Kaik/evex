package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.webhook.url-policy.*` (E33 / DEC-116, decisão D4) — **ponto de extensão documentado, SEM efeito
 * nesta entrega**: o modo da política de destino ([br.com.bbchain.evex.indexer.domain.webhook.OutboundUrlPolicy])
 * aplicada à URL CADASTRADA do webhook (`WebhookConfigService.validateUrl` hoje só exige http/https absoluto).
 * `OFF` (padrão, preserva uso com localhost em dev), `WARN` (só registra) ou `ENFORCE` (rejeita o cadastro).
 * Valor livre (string extensível) — nenhum código lê o modo ainda; o enforcement na URL cadastrada fica para
 * uma entrega futura. A política JÁ vale, sempre, para o `overrideUrl` do reenvio, independente deste modo.
 */
@Component
@ConfigurationProperties(prefix = "indexer.webhook.url-policy")
class WebhookUrlPolicyProperties {
    var mode: String = "OFF"
}
