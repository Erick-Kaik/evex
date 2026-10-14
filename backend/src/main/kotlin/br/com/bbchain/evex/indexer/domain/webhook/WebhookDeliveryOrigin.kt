package br.com.bbchain.evex.indexer.domain.webhook

/**
 * Origem de uma [WebhookDelivery] — códigos extensíveis (string, não enum fechado): origem nova no futuro
 * = constante nova, sem migration de tipo. A coluna é a ÚNICA fonte; o `resend` do DTO é derivado
 * ([isResend]).
 */
object WebhookDeliveryOrigin {
    /** Disparo normal (casamento na ingestão/redecode). */
    const val MATCH = "MATCH"

    /** Reenvio manual (`POST /v1/webhooks/{id}/resend`). */
    const val RESEND = "RESEND"

    /** Tudo que não é [MATCH] conta como reenvio (origem desconhecida = não disparo normal). */
    fun isResend(origin: String): Boolean = origin != MATCH
}
