package br.com.bbchain.evex.indexer.config

import br.com.bbchain.evex.indexer.domain.webhook.HostResolverPort
import br.com.bbchain.evex.indexer.domain.webhook.OutboundUrlPolicy
import br.com.bbchain.evex.indexer.domain.webhook.OutboundUrlSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Liga a [OutboundUrlPolicy] (domínio puro) às properties `indexer.webhook.resend.*`. */
@Configuration
class OutboundUrlPolicyConfig {
    @Bean
    fun outboundUrlPolicy(resolver: HostResolverPort, properties: WebhookResendProperties): OutboundUrlPolicy =
        OutboundUrlPolicy(
            resolver,
            OutboundUrlSettings(allowedPorts = properties.allowedPorts.toSet(), allowlist = properties.urlAllowlist),
        )
}
