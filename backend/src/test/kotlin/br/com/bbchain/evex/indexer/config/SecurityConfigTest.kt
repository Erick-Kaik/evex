package br.com.bbchain.evex.indexer.config

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Teste de regressão pro ponto de atenção crítico da E8.1: `spring-boot-starter-security`, uma vez
 * no classpath, ativa por padrão uma tela de login + usuário gerado **se nenhum `SecurityFilterChain`
 * explícito for encontrado**. Este teste existe especificamente pra pegar essa regressão — se
 * [SecurityConfig.securityFilterChain] for removido ou perder o `permitAll()` por engano, os 4 casos
 * abaixo passam a redirecionar/401 em vez de 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecurityConfigTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    @Test
    fun `GET actuator health returns 200, no login required`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    @Test
    fun `GET v3 api-docs returns 200, no login required`() {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
    }

    /**
     * E17 / DEC-078: o `JobStatusSseController` é `@RestController` manual (fora da spec-fonte), mas o
     * `springdoc` monta o `/v3/api-docs` a partir das anotações em runtime — os 2 `/stream` têm que
     * aparecer lá (com a `@Tag`/`@Operation` do controller) pra continuarem visíveis no Swagger UI.
     */
    @Test
    fun `v3 api-docs lists the 2 SSE stream paths (springdoc via annotations)`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/v1/redecode/status/stream'].get").exists())
            .andExpect(jsonPath("$.paths['/v1/balance-monitors/backfill/status/stream'].get").exists())
    }

    @Test
    fun `GET swagger-ui index returns 200, no login required`() {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
    }

    @Test
    fun `GET a real v1 endpoint returns 200, no login required`() {
        mockMvc.perform(get("/v1/contracts")).andExpect(status().isOk)
    }

    /**
     * E17 / DEC-078: os 2 streams SSE são servidos por um `@RestController` **manual**
     * ([br.com.bbchain.evex.indexer.adapters.inbound.api.sse.JobStatusSseController]) — fora do fluxo
     * OpenAPI. Confirma que caem no `permitAll()` do padrão `/v1` como qualquer outra rota (PoC sem
     * auth), e não numa regra restritiva do `SecurityFilterChain`.
     */
    @Test
    fun `GET redecode status stream returns 200, no login required`() {
        mockMvc.perform(get("/v1/redecode/status/stream")).andExpect(status().isOk)
    }

    @Test
    fun `GET balance backfill status stream returns 200, no login required`() {
        mockMvc.perform(get("/v1/balance-monitors/backfill/status/stream")).andExpect(status().isOk)
    }
}
