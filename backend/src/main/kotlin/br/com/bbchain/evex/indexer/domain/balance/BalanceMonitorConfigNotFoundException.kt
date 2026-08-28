package br.com.bbchain.evex.indexer.domain.balance

import java.util.UUID

/** Nenhuma configuração de monitoramento de saldo cadastrada com este id. */
class BalanceMonitorConfigNotFoundException(val id: UUID) :
    RuntimeException("Configuração de monitoramento de saldo não encontrada: $id")
