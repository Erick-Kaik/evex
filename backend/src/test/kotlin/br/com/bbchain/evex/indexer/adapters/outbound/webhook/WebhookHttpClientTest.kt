package br.com.bbchain.evex.indexer.adapters.outbound.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

/**
 * `WebhookHttpClient` sets its own [org.springframework.http.client.SimpleClientHttpRequestFactory]
 * (configurable timeout, `indexer.webhook.request-timeout-ms`) on the injected `RestClient.Builder` —
 * incompatible with `MockRestServiceServer.bindTo(builder)` (E6/`BlockscoutClientTest` pattern), which
 * works by installing its own mock request factory on that same builder: whichever call happens last
 * wins, and `WebhookHttpClient`'s own factory always runs last (only resolved lazily, on first
 * `send()`). Tested instead against a real ephemeral local HTTP server (`com.sun.net.httpserver`, JDK
 * standard library, no extra dependency) — deterministic, no real network, no mocking gymnastics.
 */
class WebhookHttpClientTest {

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private fun startServer(status: Int, body: String): String {
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/hook") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        httpServer.start()
        server = httpServer
        return "http://localhost:${httpServer.address.port}/hook"
    }

    private fun client(): WebhookHttpClient = WebhookHttpClient(RestClient.builder(), WebhookProperties().apply { requestTimeoutMs = 2000 })

    @Test
    fun `a 200 response is a success, with httpStatus and responseBody`() {
        val url = startServer(200, """{"ok":true}""")

        val result = client().send(url, """{"event":"Transfer"}""")

        assertThat(result.success).isTrue()
        assertThat(result.httpStatus).isEqualTo(200)
        assertThat(result.responseBody).contains("ok")
        assertThat(result.errorMessage).isNull()
    }

    @Test
    fun `a 500 response is a failure, with httpStatus, no exception propagated`() {
        val url = startServer(500, "internal error")

        val result = client().send(url, "{}")

        assertThat(result.success).isFalse()
        assertThat(result.httpStatus).isEqualTo(500)
        assertThat(result.responseBody).contains("internal error")
    }

    @Test
    fun `a connection refused is a failure, with errorMessage, no exception propagated`() {
        // Nothing listens on this port - guaranteed connection-refused, no server needed.
        val result = client().send("http://localhost:1/hook", "{}")

        assertThat(result.success).isFalse()
        assertThat(result.httpStatus).isNull()
        assertThat(result.errorMessage).isNotNull()
    }

    @Test
    fun `a relative-only url (no host, no scheme) is a failure, no exception propagated`() {
        // Achado de revisão (reviewer, E8): RestClient.uri(...)/SimpleClientHttpRequestFactory
        // throw IllegalArgumentException (or its subtype InvalidUrlException) for a URL like this,
        // never a RestClientException - WebhookConfigService.validateUrl() already blocks this at
        // cadastro time, but this class keeps its own last-line-of-defense catch, consistent with
        // its documented contract ("nunca propaga exceção crua").
        val result = client().send("not-a-url", "{}")

        assertThat(result.success).isFalse()
        assertThat(result.httpStatus).isNull()
        assertThat(result.errorMessage).isNotNull()
    }

    @Test
    fun `a 3xx with Location is NOT followed (E33 SSRF) - the target of the redirect receives nothing`() {
        val target = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        val hits = java.util.concurrent.atomic.AtomicInteger()
        target.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        target.start()
        try {
            val redirecting = HttpServer.create(InetSocketAddress("localhost", 0), 0)
            redirecting.createContext("/hook") { exchange ->
                exchange.responseHeaders.add("Location", "http://localhost:${target.address.port}/internal")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            redirecting.start()
            server = redirecting

            val result = client().send("http://localhost:${redirecting.address.port}/hook", "{}")

            assertThat(result.success).isFalse()
            assertThat(result.httpStatus).isEqualTo(302)
            assertThat(hits.get()).isZero()
        } finally {
            target.stop(0)
        }
    }

    @Test
    fun `transport failures carry a generic errorCode - connection refused is CONNECT_FAILED, invalid url is INVALID_URL`() {
        assertThat(client().send("http://localhost:1/hook", "{}").errorCode).isEqualTo("CONNECT_FAILED")
        assertThat(client().send("not-a-url", "{}").errorCode).isEqualTo("INVALID_URL")
    }

    @Test
    fun `a read timeout is classified as TIMEOUT`() {
        val httpServer = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        httpServer.createContext("/hook") { exchange ->
            Thread.sleep(1500)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        httpServer.start()
        server = httpServer

        val slow = WebhookHttpClient(RestClient.builder(), WebhookProperties().apply { requestTimeoutMs = 300 })
        val result = slow.send("http://localhost:${httpServer.address.port}/hook", "{}")

        assertThat(result.success).isFalse()
        assertThat(result.errorCode).isEqualTo("TIMEOUT")
    }
}
