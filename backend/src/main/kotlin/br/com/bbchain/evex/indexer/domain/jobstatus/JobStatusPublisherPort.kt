package br.com.bbchain.evex.indexer.domain.jobstatus

/**
 * Porta de saída (E17 / DEC-074 / DEC-078): publica **cada transição de estado** de um job assíncrono
 * — redecode (E5, `RedecodeStatus`) e backfill de saldo (E14, `BalanceBackfillStatus`) — para quem
 * quiser consumir esse estado ao vivo (hoje: o adapter SSE em `adapters/inbound/api/sse/`).
 *
 * ## Por que uma porta, e por que ela mora no `domain/`
 *
 * Os orquestradores (`RedecodeOrchestrator`, `BalanceBackfillOrchestrator`) vivem em `application/` e
 * sabem o estado a cada passo (`tryStart` → `onBatchProcessed` → `onFinished`). O transporte desse
 * estado pro browser é `SseEmitter` — classe de `org.springframework.web.servlet.*`, que **não pode**
 * ser importada em `application/` (regra não negociável: `application`/`domain` não dependem de
 * `org.springframework.web.*`). A porta corta esse acoplamento: o orquestrador chama
 * `publish(status)` contra esta interface pura; o `Sinks`/registro de `SseEmitter` fica **inteiro no
 * adapter**.
 *
 * ## Por que NÃO usamos Reactor (`Sinks.Many`/`Flux`) na `application`
 *
 * `reactor-core` seria *tolerável* em `application` em tese (é lib de streams, não de web — mesma
 * pegada de `kotlinx.coroutines`), mas: (a) **não está no classpath** hoje (só o `reactor-bom`
 * resolvido) — puxar uma dependência nova só pra um detalhe de transporte que cabe 100% no adapter
 * não se justifica; (b) o stack é servlet MVC, cujo primitivo nativo de streaming é `SseEmitter`, que
 * é camada web e fica no adapter de qualquer jeito. Resultado: **nenhuma lib de stream na
 * `application`** — só esta interface de uma linha.
 *
 * ## Forma
 *
 * Genérica em `S` pra servir os dois DTOs de status sem duplicar a interface. As implementações
 * concretas fixam o tipo (`class RedecodeStatusSseBroker : JobStatusSseBroker<RedecodeStatus>()`),
 * e o Spring resolve a injeção por tipo genérico (`ResolvableType`, mesmo mecanismo de
 * `ApplicationListener<E>`).
 *
 * `publish` é best-effort e não-bloqueante do ponto de vista do orquestrador: uma falha de I/O num
 * cliente SSE nunca pode derrubar o job. A implementação isola cada cliente.
 *
 * `interface` simples (não `fun interface`) — é uma porta implementada por classe
 * (`JobStatusSseBroker`), nunca por lambda.
 */
interface JobStatusPublisherPort<in S : Any> {

    /**
     * Registra um novo snapshot de estado do job. Chamado pelo orquestrador logo após cada transição
     * (início, fim de lote, término). É um **snapshot completo**, não um delta — quem chegar no meio
     * do stream não perde consistência.
     */
    fun publish(status: S)
}
