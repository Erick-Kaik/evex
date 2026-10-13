package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.toReports
import br.com.bbchain.evex.indexer.domain.webhook.OutboundUrlPolicy
import br.com.bbchain.evex.indexer.domain.webhook.UrlCheck
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Pega entregas `PENDING` devidas ([WebhookDeliveryPort.findDue]) e dispara o `POST` de verdade —
 * roda **fora** de qualquer transação de banco (I/O externo lento/não confiável nunca deve segurar
 * uma transação aberta, regra não-negociável do doc: "não pode ser síncrona bloqueando a ingestão").
 *
 * Sem guarda de concorrência própria — mesma garantia natural do `@Scheduled` já estabelecida em
 * `ScheduledPollTrigger`/`BalanceSnapshotJob`: o Spring não roda 2 execuções do mesmo método `@Scheduled`
 * em paralelo.
 *
 * `try/catch` por entrega dentro do loop (E8.1) — mesma lição já aplicada em
 * `WebhookMatchingService.matchAndEnqueue` desde o início (DEC-035/DEC-040): 1 entrega com problema
 * (ex.: falha futura em `recorder.recordAttempt`, que é `@Transactional`/grava no banco) nunca deve
 * abortar o ciclo `@Scheduled` inteiro — as outras entregas devidas do mesmo lote continuam sendo
 * processadas.
 */
@Component
class WebhookDispatchJob(
    private val deliveries: WebhookDeliveryPort,
    private val webhookConfigs: WebhookConfigPort,
    private val webhookDispatchPort: WebhookDispatchPort,
    private val recorder: WebhookDeliveryRecorder,
    private val properties: WebhookProperties,
    private val enrichmentResolver: WebhookEnrichmentResolver,
    private val urlPolicy: OutboundUrlPolicy,
    private val resendProperties: WebhookResendProperties,
) {
    private val logger = LoggerFactory.getLogger(WebhookDispatchJob::class.java)

    @Scheduled(fixedDelayString = "\${indexer.webhook.dispatch-interval-ms}")
    fun dispatch() {
        val due = deliveries.findDue(properties.dispatchBatchSize)
        // D9 (E33): memoização por CICLO de dispatch (mesma chamada no mesmo bloco roda 1x, inclusive a falha) —
        // um contexto novo por ciclo, nunca compartilhado entre ciclos/threads. Sem circuit-breaker no disparo normal.
        val context = EnrichmentExecutionContext()
        for (delivery in due) {
            dispatchSafely(delivery, context)
        }
    }

    private fun dispatchSafely(delivery: WebhookDelivery, context: EnrichmentExecutionContext) {
        try {
            val config = webhookConfigs.findById(delivery.webhookConfigId)
            if (config == null) {
                logger.warn("WebhookDelivery {} referencia um webhookConfigId {} que não existe mais — pulando.", delivery.id, delivery.webhookConfigId)
                return
            }
            // E19/DEC-082 — Fase 2 do render em 2 fases: resolve {{enriched.*}} ao vivo (referência viva
            // ao perfil, não snapshot) só agora, fora de transação, imediatamente antes do POST. Config
            // sem enrichmentProfileIds -> resolve() devolve o payload sem tocar (regressão zero).
            // E33: o modo de bloco vem da entrega (reenvio); entrega sem modo (disparo normal/antiga) = EVENT_BLOCK,
            // idêntico ao comportamento anterior. `resolveDetailed` devolve o relatório por chamada (D5) — o
            // disparo normal continua SEM circuit-breaker e com `null` no placeholder que falha (DEC-057).
            val resolution = enrichmentResolver.resolveDetailed(delivery, config.enrichmentProfileIds, delivery.blockSpec(), context = context)
            val payload = resolution.payload
            val reports = resolution.outcomes.toReports()
            val blockUsed = reports.mapNotNull { it.blockUsed }.distinct().singleOrNull()

            val override = delivery.targetUrlOverride
            val result = if (override == null) webhookDispatchPort.send(config.url, payload) else sendToOverride(override, payload)
            recorder.recordAttempt(delivery, result, reports.ifEmpty { null }, blockUsed, payload)
        } catch (ex: Exception) {
            logger.warn("Falha ao processar WebhookDelivery {} neste ciclo de dispatch — seguindo pro próximo item do lote: {}", delivery.id, ex.message)
        }
    }

    /**
     * Entrega com URL de teste (E33 / DEC-116): a política de destino é REVALIDADA agora, imediatamente antes do
     * envio (o DNS pode ter mudado desde o enqueue); a capacidade desligada depois do enqueue também bloqueia.
     * Falha de política = tentativa FAILED com código genérico, SEM enviar. Para o override, a resposta nunca é
     * lida/gravada (fecha SSRF de leitura) e a falha de transporte vira um código genérico, sem mensagem de rede.
     */
    private fun sendToOverride(url: String, payload: String): WebhookAttemptResult {
        if (!resendProperties.allowUrlOverride || urlPolicy.check(url) !is UrlCheck.Allowed) {
            return WebhookAttemptResult(success = false, httpStatus = null, responseBody = null, errorMessage = OVERRIDE_URL_BLOCKED)
        }
        val raw = webhookDispatchPort.send(url, payload)
        return raw.copy(
            responseBody = null,
            errorCode = null,
            errorMessage = if (raw.success) null else raw.errorCode?.let { "OVERRIDE_$it" } ?: raw.errorMessage?.let { OVERRIDE_UNREACHABLE },
        )
    }

    private fun WebhookDelivery.blockSpec(): EnrichmentBlockSpec =
        enrichmentBlockMode?.let { EnrichmentBlockSpec(mode = it, blockNumber = enrichmentBlockNumber) } ?: EnrichmentBlockSpec.EventBlock

    private companion object {
        const val OVERRIDE_URL_BLOCKED = "OVERRIDE_URL_BLOCKED"
        const val OVERRIDE_UNREACHABLE = "OVERRIDE_UNREACHABLE"
    }
}
