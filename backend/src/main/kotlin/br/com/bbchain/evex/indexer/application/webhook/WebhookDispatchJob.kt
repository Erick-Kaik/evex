package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
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
 * `BlockchainPoller`/`BalanceSnapshotJob`: o Spring não roda 2 execuções do mesmo método `@Scheduled`
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
) {
    private val logger = LoggerFactory.getLogger(WebhookDispatchJob::class.java)

    @Scheduled(fixedDelayString = "\${indexer.webhook.dispatch-interval-ms}")
    fun dispatch() {
        val due = deliveries.findDue(properties.dispatchBatchSize)
        for (delivery in due) {
            dispatchSafely(delivery)
        }
    }

    private fun dispatchSafely(delivery: WebhookDelivery) {
        try {
            val config = webhookConfigs.findById(delivery.webhookConfigId)
            if (config == null) {
                logger.warn("WebhookDelivery {} referencia um webhookConfigId {} que não existe mais — pulando.", delivery.id, delivery.webhookConfigId)
                return
            }
            val result = webhookDispatchPort.send(config.url, delivery.payload)
            recorder.recordAttempt(delivery, result)
        } catch (ex: Exception) {
            logger.warn("Falha ao processar WebhookDelivery {} neste ciclo de dispatch — seguindo pro próximo item do lote: {}", delivery.id, ex.message)
        }
    }
}
