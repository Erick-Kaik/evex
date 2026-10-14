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
 *
 * [batch] (DEC-110): `true` quando [valueParam] (e [tokenIdParam], se informado) referenciam parâmetros
 * array (ex.: `uint256[]`) — cada posição vira um lançamento independente, aplicado com o mesmo
 * [behavior]. `false` (default) = parâmetros escalares.
 *
 * Campos de backfill de saldo sob demanda (E14, DEC-066):
 * - [backfillWatermarkBlock]: bloco do cursor de ingestão no instante em que a config foi criada
 *   (`register()`), **imutável** depois. O backfill só reprojeta eventos com
 *   `blockNumber <= backfillWatermarkBlock` — tudo a partir daí é da ingestão ao vivo, sem
 *   sobreposição/contagem dupla. `-1` = cursor ainda em `-1` na criação (nada a reprojetar).
 * - [backfillCursorBlock]/[backfillCursorLogIndex]: progresso resumível do backfill, avançado na
 *   **mesma transação** em que os deltas do lote são aplicados. `-1`/`-1` = nada reprojetado ainda.
 *   Torna o backfill seguro de re-disparar (vira no-op ao alcançar a marca d'água).
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
    val backfillWatermarkBlock: Long = -1,
    val backfillCursorBlock: Long = -1,
    val backfillCursorLogIndex: Long = -1,
    val batch: Boolean = false,
)
