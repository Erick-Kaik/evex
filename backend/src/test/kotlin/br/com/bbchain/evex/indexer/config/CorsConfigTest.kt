package br.com.bbchain.evex.indexer.config

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Prova que o CORS de [SecurityConfig] funciona de verdade contra as rotas sob `/v1` — origem
 * configurada (`indexer.cors.allowed-origins`, default `http://localhost:5173` em `application.yml`)
 * recebe o header, origem não-configurada não recebe. Mesmo cenário que um `curl` manual com header
 * `Origin` e método `OPTIONS` validaria (critério de aceite da E8.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CorsConfigTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val allowedOrigin = "http://localhost:5173"
    private val disallowedOrigin = "http://evil.test"

    @Test
    fun `GET from the configured origin gets Access-Control-Allow-Origin echoed back`() {
        mockMvc.perform(get("/v1/contracts").header("Origin", allowedOrigin))
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", allowedOrigin))
    }

    @Test
    fun `GET from a non-configured origin does not get Access-Control-Allow-Origin, request rejected`() {
        // Spring's DefaultCorsProcessor rejects (403) actual (non-preflight) requests from an origin
        // that doesn't match the configured CorsConfiguration — not just preflight OPTIONS.
        mockMvc.perform(get("/v1/contracts").header("Origin", disallowedOrigin))
            .andExpect(status().isForbidden)
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    }

    @Test
    fun `OPTIONS preflight from the configured origin succeeds and echoes the allowed methods`() {
        mockMvc.perform(
            options("/v1/contracts")
                .header("Origin", allowedOrigin)
                .header("Access-Control-Request-Method", "POST"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", allowedOrigin))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
    }

    @Test
    fun `a request without an Origin header (not a CORS request) is never blocked by the CORS filter`() {
        mockMvc.perform(get("/v1/contracts")).andExpect(status().isOk)
    }
}
