package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.BalanceBackfillStatus as BalanceBackfillStatusDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.RedecodeStatus as RedecodeStatusDto
import br.com.bbchain.evex.indexer.domain.balance.BalanceBackfillStatus
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeStatus

/**
 * Mapeadores compartilhados status de **domínio** → **DTO gerado** dos endpoints REST de status de
 * job (E17 / DEC-078).
 *
 * Ponto único de verdade do formato: são chamados tanto pelas `*ApiServiceImpl` (respostas REST de
 * `GET /v1/redecode/status` e `GET /v1/balance-monitors/backfill/status`) quanto pelos
 * [br.com.bbchain.evex.indexer.adapters.inbound.api.sse.JobStatusSseBroker]s (campo `data:` do frame
 * `event: status`). Assim o payload do SSE é **byte-idêntico** ao da resposta REST — o mesmo objeto,
 * serializado pelo mesmo Jackson. Se um dia o DTO mudar, muda num lugar só.
 */
fun RedecodeStatus.toWireDto(): RedecodeStatusDto = RedecodeStatusDto(
    running = running,
    processedCount = processedCount,
    decodedCount = decodedCount,
    remainingCount = remainingCount,
)

fun BalanceBackfillStatus.toWireDto(): BalanceBackfillStatusDto = BalanceBackfillStatusDto(
    running = running,
    processedCount = processedCount,
    deltaCount = deltaCount,
    remainingCount = remainingCount,
    caughtUp = caughtUp,
    configId = configId,
)
