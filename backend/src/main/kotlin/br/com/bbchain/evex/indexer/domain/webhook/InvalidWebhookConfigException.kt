package br.com.bbchain.evex.indexer.domain.webhook

/** Payload de cadastro/edição de webhook inválido — hoje só `payloadTemplate` que não renderiza como JSON válido. */
class InvalidWebhookConfigException(message: String) : RuntimeException(message)
