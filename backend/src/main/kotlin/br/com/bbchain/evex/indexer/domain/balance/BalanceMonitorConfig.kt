package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.Network
import java.time.Instant
import java.util.UUID

/**
 * Configuração de monitoramento de saldo cadastrada pelo usuário (doc de produto §4.4) — associa um
 * `(contractAddress, eventName)` a um [BalanceBehavior] e os nomes dos parâmetros do evento decodificado
 * que carregam a carteira/valor/tokenId.
 *
 * Combinação de campos válida por [behavior] (validada em `application/balance/BalanceMonitorConfigService`,
 * não aqui — `domain` não lança exceção de validação de payload, só carrega o tipo):
 * - `TRANSFER`: [fromParam]/[toParam] obrigatórios, [walletParam] ausente.
 * - `CREDIT`/`DEBIT`/`DELTA`: [walletParam] obrigatório, [fromParam]/[toParam] ausentes.
 *
 * [tokenIdParam] é opcional em qualquer behavior — `null` = não aplicável (ex.: ERC20, sem noção de
 * tokenId). `valueParam` é sempre obrigatório: é o parâmetro que carrega o montante/delta em todos os
 * 4 behaviors.
 */
data class BalanceMonitorConfig(
    val id: UUID,
    val contractAddress: String,
    val network: Network,
    val eventName: String,
    val behavior: BalanceBehavior,
    val walletParam: String?,
    val fromParam: String?,
    val toParam: String?,
    val valueParam: String,
    val tokenIdParam: String?,
    val active: Boolean,
    val createdAt: Instant,
)
