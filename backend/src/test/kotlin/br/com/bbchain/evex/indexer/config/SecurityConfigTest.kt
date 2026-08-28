package br.com.bbchain.evex.indexer.config

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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

    @Test
    fun `GET swagger-ui index returns 200, no login required`() {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk)
    }

    @Test
    fun `GET a real v1 endpoint returns 200, no login required`() {
        mockMvc.perform(get("/v1/contracts")).andExpect(status().isOk)
    }
}
