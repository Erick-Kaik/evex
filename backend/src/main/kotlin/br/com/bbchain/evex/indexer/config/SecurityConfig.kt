package br.com.bbchain.evex.indexer.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * CORS via Spring Security — decisão tomada com o usuário na E8.1, não um `WebMvcConfigurer` solto,
 * alinhada com o que `backend/CLAUDE.md` ("O que precisa ser adaptado") já antecipava desde a análise
 * inicial do projeto: *"`WebSecurityConfig`: remover [JWT/Keycloak do código base]; manter só CORS
 * pro front consumir"*.
 *
 * **CRÍTICO**: `spring-boot-starter-security`, uma vez no classpath, ativa por padrão uma tela de
 * login básica + usuário gerado (`user`/senha aleatória no log) **se nenhum `SecurityFilterChain`
 * explícito for encontrado**. O [securityFilterChain] abaixo é **100% permissivo**
 * (`anyRequest().permitAll()`, CSRF desabilitado — API stateless, sem sessão/cookie) — CORS é a
 * única coisa que esse filtro de fato faz. Isso **não contradiz** "sem autenticação/autorização na
 * PoC" (`../../CLAUDE.md` raiz): nenhuma credencial é exigida, nenhuma requisição é rejeitada por
 * falta de auth — Spring Security aqui é só o mecanismo correto de aplicar CORS na cadeia de filtros
 * (evita o cenário comum de um preflight `OPTIONS` sendo barrado por um filtro de segurança
 * adicionado depois, se a PoC evoluir pra ter auth de verdade). Ver `SecurityConfigTest`/
 * `CorsConfigTest` para os testes de regressão que existem especificamente pra este risco
 * (`/actuator/health`/Swagger UI precisam continuar abertos, sem exigir login).
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun corsConfigurationSource(props: CorsProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOrigins = props.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            // E33: o 429 (RATE_LIMITED / RESEND_QUEUE_FULL) traz Retry-After; sem expô-lo o navegador não o lê.
            exposedHeaders = listOf("Retry-After")
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/v1/**", configuration)
        }
    }

    /**
     * `corsSource` injetado por **tipo qualificado pelo nome do bean** (`@Qualifier`), não por tipo
     * puro — `CorsConfigurationSource` não é implementado só pelo bean acima: o Spring MVC também
     * registra `mvcHandlerMappingIntrospector` (`HandlerMappingIntrospector`, usado pra resolver
     * `@CrossOrigin` por handler) como um bean desse mesmo tipo. Sem o `@Qualifier`, o Spring não
     * consegue decidir entre os dois (`NoUniqueBeanDefinitionException`) — achado só em teste de
     * integração real (`@SpringBootTest`), nenhum teste de unidade pega esse tipo de ambiguidade de
     * autowiring.
     */
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        @Qualifier("corsConfigurationSource") corsSource: CorsConfigurationSource,
    ): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsSource) }
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }
}
