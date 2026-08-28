package br.com.bbchain.evex.indexer.domain.webhook

import java.util.UUID

/** Nenhuma configuração de webhook cadastrada com este id. */
class WebhookConfigNotFoundException(val id: UUID) : RuntimeException("Webhook não encontrado: $id")
