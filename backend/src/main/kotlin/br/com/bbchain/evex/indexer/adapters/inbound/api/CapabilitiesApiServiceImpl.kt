package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.CapabilitiesApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Capabilities
import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import org.springframework.stereotype.Service

/** Implementação fina de [CapabilitiesApiService]: só espelha as properties `indexer.webhook.resend.*` (E33). */
@Service
class CapabilitiesApiServiceImpl(
    private val resendProperties: WebhookResendProperties,
) : CapabilitiesApiService {

    override fun getCapabilities(): Capabilities = Capabilities(
        webhookUrlOverrideEnabled = resendProperties.allowUrlOverride,
        webhookResendMaxEventsPerRequest = resendProperties.maxEventsPerRequest,
        webhookPreviewMaxEvents = resendProperties.previewMaxEvents,
        webhookResendMaxPendingResends = resendProperties.maxPendingResends,
        webhookResendMaxPerMinutePerWebhook = resendProperties.maxResendsPerMinutePerWebhook,
        webhookMaxRequestsPerMinutePerInstance = resendProperties.maxRequestsPerMinutePerInstance,
    )
}
