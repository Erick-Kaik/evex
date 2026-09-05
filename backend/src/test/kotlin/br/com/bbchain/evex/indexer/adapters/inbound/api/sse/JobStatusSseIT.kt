package br.com.bbchain.evex.indexer.adapters.inbound.api.sse

import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource

/**
 * Teste de integração dos 2 streams SSE de status (E17 / DEC-078) — Tomcat real em porta aleatória +
 * Postgres via Testcontainers ([AbstractIntegrationTest]), cliente HTTP do JDK lendo o `text/event-stream`
 * linha a linha. Um `TestRestTemplate`/`MockMvc` assíncrono não lê frames incrementais de um stream
 * aberto de forma limpa — o `HttpClient.BodyHandlers.ofLines()` sim.
 *
 * `indexer.sse.emitter-timeout-ms` = 20 s: **maior** que todas as janelas `await().atMost(...)` abaixo
 * (a maior é 10 s, pro `event: done`) pra o servidor não fechar o stream antes do frame esperado
 * chegar — num CI carregado / Testcontainers frio o `onFinished` pode demorar. Ao fim do teste o
 * emitter fecha sozinho no timeout (o `thenAccept` que drena as linhas não vaza thread além disso).
 * `heartbeat-interval-ms` curto (300 ms) pra o teste de heartbeat não esperar os 15 s de produção.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "indexer.sse.emitter-timeout-ms=20000",
        "indexer.sse.heartbeat-interval-ms=300",
    ],
)
class JobStatusSseIT(
    @Autowired val environment: Environment,
    @Autowired val redecodeOrchestrator: RedecodeOrchestrator,
) : AbstractIntegrationTest() {

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private val baseUrl: String
        get() = "http://localhost:${environment.getProperty("local.server.port")}"

    /** Abre o stream e devolve uma lista que vai sendo preenchida com cada linha SSE recebida. */
    private fun openStream(path: String): CopyOnWriteArrayList<String> {
        val lines = CopyOnWriteArrayList<String>()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .header("Accept", "text/event-stream")
            .GET()
            .build()
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
            .thenAccept { response -> response.body().forEach(lines::add) }
        return lines
    }

    @Test
    fun `redecode stream sends an initial status frame on connect`() {
        val lines = openStream("/v1/redecode/status/stream")

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(lines).contains("event:status")
            assertThat(lines.any { it.startsWith("data:") && it.contains("\"running\"") }).isTrue()
        }
    }

    @Test
    fun `redecode stream sends periodic heartbeat frames`() {
        val lines = openStream("/v1/redecode/status/stream")

        await().atMost(Duration.ofSeconds(5)).until { lines.count { it == "event:heartbeat" } >= 2 }
    }

    @Test
    fun `redecode stream sends status updates and a done frame while a redecode runs`() {
        val lines = openStream("/v1/redecode/status/stream")
        await().atMost(Duration.ofSeconds(5)).until { lines.contains("event:status") }

        redecodeOrchestrator.tryStart(RedecodeFilter())

        await().atMost(Duration.ofSeconds(10)).until { lines.contains("event:done") }
        assertThat(lines.count { it == "event:status" }).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `balance backfill stream sends an initial status frame on connect`() {
        val lines = openStream("/v1/balance-monitors/backfill/status/stream")

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            assertThat(lines).contains("event:status")
            assertThat(lines.any { it.startsWith("data:") && it.contains("\"running\"") }).isTrue()
        }
    }
}
