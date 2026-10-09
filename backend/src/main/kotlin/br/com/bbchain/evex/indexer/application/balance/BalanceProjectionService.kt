package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.application.common.WarnLogDeduper
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceBehaviorStrategy
import br.com.bbchain.evex.indexer.domain.balance.BalanceDelta
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.balance.BatchEventExpander
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Projeta saldo a partir de eventos recém-decodificados — chamada por [br.com.bbchain.evex.indexer.
 * adapters.outbound.blockchain.IngestionBatchProcessor] (E4, eventos novos) e por [br.com.bbchain.evex.
 * indexer.application.redecode.RedecodeBatchProcessor] (E5, eventos que acabaram de virar `decoded=true`
 * neste lote de redecode) — nunca eventos já existentes/já decodificados antes, essa filtragem já é
 * responsabilidade de quem chama.
 *
 * **Sem `@Transactional` próprio** — sempre roda dentro da transação já aberta pelo chamador (ambos
 * `@Transactional`): nada de saldo é persistido se a ingestão/redecode do lote falhar e a transação der
 * rollback, mesma garantia que já vale pros próprios eventos.
 */
@Service
class BalanceProjectionService(
    creditBehavior: CreditBehavior,
    debitBehavior: DebitBehavior,
    transferBehavior: TransferBehavior,
    deltaBehavior: DeltaBehavior,
    private val configs: BalanceMonitorConfigPort,
    private val balances: AssetBalancePort,
    indexerProperties: IndexerProperties,
) {
    private val logger = LoggerFactory.getLogger(BalanceProjectionService::class.java)

    /** E11 (resíduo de DEC-035) — rate-limita os `WARN` abaixo por `balanceMonitorConfig.id`. */
    private val warnLogDeduper = WarnLogDeduper(indexerProperties.warnLogDedupeInterval)

    private val strategies: Map<BalanceBehavior, BalanceBehaviorStrategy> = mapOf(
        BalanceBehavior.CREDIT to creditBehavior,
        BalanceBehavior.DEBIT to debitBehavior,
        BalanceBehavior.TRANSFER to transferBehavior,
        BalanceBehavior.DELTA to deltaBehavior,
    )

    /**
     * Para cada evento `decoded=true` do lote, busca configs ativas pro par `(contractAddress,
     * eventName)` e acumula os deltas de todas elas; no fim, 1 única chamada
     * `AssetBalancePort.applyDeltas` com todos os deltas do lote inteiro (mesclagem por chave fica a
     * cargo do adapter, não deste service).
     */
    fun projectBatch(events: List<BlockchainEvent>) {
        val deltas = mutableListOf<BalanceDelta>()
        for (event in events) {
            val eventName = event.eventName
            if (!event.decoded || eventName == null) continue
            val activeConfigs = configs.findActive(event.contractAddress, eventName)
            for (config in activeConfigs) {
                deltas += applySafely(config, event)
            }
        }
        if (deltas.isNotEmpty()) {
            balances.applyDeltas(deltas)
        }
    }

    /**
     * Backfill de saldo sob demanda (E14, DEC-066): reprojeta uma página de eventos **já
     * decodificados** contra **uma única** [config] — nunca [projectBatch], que faz
     * `configs.findActive()` e reaplicaria os deltas de outras configs já existentes do mesmo par
     * `(contractAddress, eventName)` (contagem dupla). Filtra `event.decoded && event.eventName ==
     * config.eventName` (o `contractAddress` já vem filtrado da query de [config
     * .backfillCursorBlock] pra frente), acumula via o mesmo [applySafely] de [projectBatch] (reusa
     * tratamento de erro + [warnLogDeduper]), 1 chamada `AssetBalancePort.applyDeltas`.
     *
     * Sem `@Transactional` próprio — roda dentro da transação de `BalanceBackfillBatchProcessor
     * .processBatch`, a mesma que avança o cursor de progresso da config: ou os deltas do lote e o
     * cursor avançam juntos, ou nada avança. Retorna o nº de deltas aplicados (pra contador de
     * status).
     */
    fun projectHistoryForConfig(events: List<BlockchainEvent>, config: BalanceMonitorConfig): Int {
        val deltas = mutableListOf<BalanceDelta>()
        for (event in events) {
            if (!event.decoded || event.eventName != config.eventName) continue
            deltas += applySafely(config, event)
        }
        if (deltas.isNotEmpty()) {
            balances.applyDeltas(deltas)
        }
        return deltas.size
    }

    /**
     * Achado da revisão da E7 (`reviewer`): `BalanceBehaviorStrategy` (DEC-034) só é garantidamente
     * "nunca lança exceção" para parâmetro **ausente** (`null`) — `toBalanceAmount()` (achado #1)
     * lança `IllegalArgumentException` para um parâmetro **presente mas de tipo incompatível** (ex.:
     * `valueParam` apontando pra um campo `bool`/array/endereço em vez de um número), e nenhuma das 4
     * implementações capturava isso. Sem essa proteção aqui, uma config de saldo mal configurada
     * derrubava a transação inteira de ingestão/redecode (`@Transactional` de
     * `IngestionBatchProcessor`/`RedecodeBatchProcessor`) — pior ainda, como o cursor global (DEC-002)
     * só avança dentro da mesma transação, o poller ficaria preso pra sempre na mesma janela de blocos
     * a cada novo ciclo (`PollTickService.tick()` engole a exceção e loga, mas o cursor nunca avança),
     * exatamente o cenário que DEC-034 queria evitar. Fix: qualquer exceção de uma `apply()` é
     * capturada aqui, logada em `WARN` (com o `id` da config e o evento, pra dar rastro — sem isso não
     * havia log nenhum de config mal configurada, mesmo no caminho silencioso de `null`), e tratada
     * como "sem delta pra esta config", sem afetar as demais configs/eventos do lote.
     *
     * E11 (item 3, resíduo de DEC-034): o caso de parâmetro **ausente** — indistinguível de `null`
     * legítimo, tratado silenciosamente pelas 4 `BalanceBehaviorStrategy` via `?: return emptyList()`
     * — passa a logar `WARN` também, quando uma config **ativa** (já bateu `contractAddress`+
     * `eventName`) processa um evento e não gera nenhum delta. E11 (item 4, resíduo de DEC-035): os
     * dois `WARN` abaixo passam por [warnLogDeduper] — uma config persistentemente mal configurada
     * (ex.: typo nunca corrigido) não gera mais 1 linha de log a cada evento que bate nela.
     *
     * DEC-110: evento inválido em lote (tamanhos diferentes, não-lista, elemento não numérico) e
     * lista chegando em config `batch=false` lançam `BatchEventInvalidException`, que cai neste mesmo
     * `catch`: o evento INTEIRO é descartado (atomicidade) com WARN deduplicado, sem derrubar o lote.
     */
    private fun applySafely(config: BalanceMonitorConfig, event: BlockchainEvent): List<BalanceDelta> =
        try {
            val computed = computeDeltas(config, event)
            val result = computed.deltas
            // Lote legitimamente vazio (ids/values == []) é no-op, não config mal configurada: sem WARN.
            if (result.isEmpty() && !computed.emptyBatch && warnLogDeduper.shouldLog(config.id)) {
                logger.warn(
                    "balanceMonitorConfig={} (contractAddress={}, eventName={}) não gerou nenhum delta pro evento txHash={}, logIndex={} — verifique se walletParam/fromParam/toParam/valueParam realmente existem nos parâmetros decodificados deste evento.",
                    config.id,
                    event.contractAddress,
                    event.eventName,
                    event.txHash,
                    event.logIndex,
                )
            }
            result
        } catch (ex: Exception) {
            if (warnLogDeduper.shouldLog(config.id)) {
                logger.warn(
                    "Falha ao projetar saldo pra balanceMonitorConfig={} (contractAddress={}, eventName={}, txHash={}, logIndex={}): {}",
                    config.id,
                    event.contractAddress,
                    event.eventName,
                    event.txHash,
                    event.logIndex,
                    ex.message,
                )
            }
            emptyList()
        }

    /**
     * DEC-110: `batch=true` expande o evento em N eventos escalares (um por posição) e aplica a MESMA
     * strategy do behavior em cada um, concatenando os deltas; `batch=false` valida que valor/tokenId
     * são escalares (lista -> evento inválido, em vez de gerar um tokenId lixo "[2, 3]").
     */
    private fun computeDeltas(config: BalanceMonitorConfig, event: BlockchainEvent): ComputedDeltas {
        val strategy = strategies.getValue(config.behavior)
        val params = event.decodedParameters ?: return ComputedDeltas(strategy.apply(config, event))
        if (!config.batch) {
            BatchEventExpander.requireScalar(config, params)
            return ComputedDeltas(strategy.apply(config, event))
        }
        val expanded = BatchEventExpander.expand(config, params)
        val deltas = expanded.flatMap { scalar -> strategy.apply(config, event.copy(decodedParameters = scalar)) }
        return ComputedDeltas(deltas, emptyBatch = expanded.isEmpty())
    }

    /** Deltas calculados para um evento; [emptyBatch] = o lote veio legitimamente vazio (no-op, DEC-110). */
    private data class ComputedDeltas(val deltas: List<BalanceDelta>, val emptyBatch: Boolean = false)
}
