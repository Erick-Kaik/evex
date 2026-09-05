package br.com.bbchain.evex.indexer.adapters.inbound.api.sse

import br.com.bbchain.evex.indexer.application.balance.BalanceBackfillOrchestrator
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * **Único `@RestController` manual do projeto** (E17 / DEC-078). Serve os 2 streams SSE de status de
 * job. Não passa pelo fluxo OpenAPI (`*ApiServiceImpl` gerado) porque:
 *
 * - o `openapi-generator` (`kotlin-spring`) não modela `text/event-stream` / retorno [SseEmitter] —
 *   geraria um método de interface com retorno de modelo único, incompatível com um stream aberto;
 * - o frontend consome via `EventSource` nativo, **nunca** pelo cliente `typescript-axios` gerado —
 *   não há ganho de geração de cliente;
 * - o `springdoc` monta o `/v3/api-docs` + Swagger UI a partir das **anotações em runtime** (não do
 *   `.yaml` estático) — estas rotas aparecem no Swagger UI mesmo sem estar na spec-fonte.
 *
 * A spec-fonte (`swagger/base/base.yaml`) tem um comentário sob `paths:` apontando pra cá. Ver
 * DEC-078 pra o registro completo da exceção à regra "nunca `@RestController` manual"
 * (DEC-012/DEC-013, `docs/hexagonal-architecture.md`).
 *
 * Fino como um `*ApiServiceImpl` deve ser: lê o snapshot atual do orquestrador (`application/`) e
 * delega a abertura do stream pro broker. Zero regra de negócio.
 */
@RestController
@Tag(
    name = "Job status (SSE)",
    description = "Streams `text/event-stream` do status de redecode e backfill. Servidos por " +
        "controller manual (E17/DEC-078) — os endpoints REST `/status` correspondentes seguem como fallback.",
)
class JobStatusSseController(
    private val redecodeOrchestrator: RedecodeOrchestrator,
    private val redecodeBroker: RedecodeStatusSseBroker,
    private val balanceBackfillOrchestrator: BalanceBackfillOrchestrator,
    private val balanceBackfillBroker: BalanceBackfillStatusSseBroker,
) {

    /**
     * `GET /v1/redecode/status/stream` — stream do [RedecodeStatus][br.com.bbchain.evex.indexer.domain.redecode.RedecodeStatus].
     * Frame inicial = `redecodeOrchestrator.status()` (estado atual, mesmo ocioso); depois um
     * `event: status` por transição + `event: heartbeat` periódico + `event: done` quando o job
     * termina. REST `GET /v1/redecode/status` continua como fallback.
     */
    @Operation(
        summary = "Stream SSE do status de redecode",
        description = "Frame inicial com o estado atual, depois `event: status` por transição, " +
            "`event: heartbeat` periódico e `event: done` ao terminar o job. Fallback: `GET /v1/redecode/status`.",
    )
    @GetMapping("/v1/redecode/status/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamRedecodeStatus(): SseEmitter = redecodeBroker.open(redecodeOrchestrator.status())

    /**
     * `GET /v1/balance-monitors/backfill/status/stream` — stream do
     * [BalanceBackfillStatus][br.com.bbchain.evex.indexer.domain.balance.BalanceBackfillStatus]
     * **global** (DEC-068: 1 job por instância; o `configId` no payload diz qual config roda).
     * Frame inicial = `balanceBackfillOrchestrator.globalStatus()`.
     */
    @Operation(
        summary = "Stream SSE do status de backfill de saldo (global)",
        description = "Job único por instância (DEC-068). O `configId` no payload diz qual config " +
            "reprojeta agora. Fallback: `GET /v1/balance-monitors/backfill/status`.",
    )
    @GetMapping(
        "/v1/balance-monitors/backfill/status/stream",
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun streamBalanceBackfillStatus(): SseEmitter =
        balanceBackfillBroker.open(balanceBackfillOrchestrator.globalStatus())
}
