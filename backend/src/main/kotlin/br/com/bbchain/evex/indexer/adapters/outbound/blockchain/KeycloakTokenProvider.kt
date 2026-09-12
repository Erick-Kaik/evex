package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import br.com.bbchain.evex.indexer.config.KeycloakAuthProperties
import java.time.Clock
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

/**
 * Gerencia o token usado para autenticar a conexão RPC com a rede Besu quando
 * `indexer.blockchain.keycloak.token-url` está configurado (E27 complemento/DEC-095) — reimplementação
 * do `ServiceTokenProvider` do código base Anbima (`keeper/auth/ServiceTokenProvider.kt`), trocando
 * `WebClient` (WebFlux, fora do classpath deste projeto) por Spring `RestClient` (mesmo client HTTP já
 * usado por [BlockscoutClient][br.com.bbchain.evex.indexer.adapters.outbound.discovery.BlockscoutClient]/
 * `WebhookHttpClient`). **Não é o BBChain Keeper** — só a peça de obtenção de token via Keycloak,
 * reaproveitada para autenticar o próprio `Web3jBlockchainClient`/`WebSocketPollTrigger` (Web3j direto).
 *
 * Máquina de estados (a cada [authorizationHeader]), idêntica à do Anbima:
 * - access válido (agora < `accessExpiry - refreshSkew`) → usa;
 * - senão refresh válido → tenta refresh (rotaciona o `refresh_token` só se vier um novo);
 * - refresh falhou (`RestClientResponseException`, ex. `invalid_grant`) → fallback pra login completo;
 * - senão (sem refresh válido) → login completo.
 *
 * Thread-safe / single-flight via [ReentrantLock]: [Web3jBlockchainClient] chama [authorizationHeader]
 * antes de cada request RPC (o token pode expirar em minutos, diferente de um PAT estático) — o lock
 * evita duas renovações concorrentes disparadas por chamadas paralelas (ex. poller + reindexação).
 */
@Component
class KeycloakTokenProvider(
    private val properties: BlockchainProperties,
    restClientBuilder: RestClient.Builder,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(KeycloakTokenProvider::class.java)

    // Sem customização de conversores: o builder default (via bean autoconfigurado do Spring Boot ou
    // um RestClient.builder() cru, ambos os casos usados nos testes) já registra o form converter
    // (necessário pro POST application/x-www-form-urlencoded de login/refresh) e um conversor JSON
    // capaz de desserializar KeycloakTokenResponse (um data class comum, não uma classe específica de
    // Jackson 2 como o JsonNode do BlockscoutClient — não precisa forçar MappingJackson2HttpMessageConverter
    // aqui). Confirmado via source real do Spring Framework 7.0.9
    // (HttpMessageConverters.forClient().registerDefaults() inclui AllEncompassingFormHttpMessageConverter)
    // antes de escrever este código.
    private val restClient: RestClient = restClientBuilder.build()
    private val lock = ReentrantLock()

    private var accessToken: String? = null
    private var accessExpiry: Instant = Instant.EPOCH
    private var refreshToken: String? = null
    private var refreshExpiry: Instant = Instant.EPOCH

    /** Header pronto pra injetar no `Authorization` da chamada RPC ao nó Besu. */
    fun authorizationHeader(): String = "Bearer ${validAccessToken()}"

    private fun validAccessToken(): String = lock.withLock {
        val config = properties.keycloak
        val now = Instant.now(clock)

        accessToken?.let { token ->
            if (now.isBefore(accessExpiry.minus(config.refreshSkew))) {
                return token
            }
        }

        if (refreshToken != null && now.isBefore(refreshExpiry)) {
            try {
                store(refresh(config))
                return accessToken!!
            } catch (ex: RestClientResponseException) {
                logger.warn("Refresh do token Keycloak falhou ({}); fazendo login completo.", ex.statusCode)
            }
        }

        store(login(config))
        accessToken!!
    }

    private fun login(config: KeycloakAuthProperties): KeycloakTokenResponse {
        logger.info("Login no Keycloak (grant_type=password) em {}", config.tokenUrl)
        val form = baseForm(config).apply {
            add("grant_type", "password")
            add("username", config.username)
            add("password", config.password)
        }
        return requestToken(config.tokenUrl, form)
    }

    private fun refresh(config: KeycloakAuthProperties): KeycloakTokenResponse {
        logger.info("Refresh do token Keycloak (grant_type=refresh_token).")
        val form = baseForm(config).apply {
            add("grant_type", "refresh_token")
            add("refresh_token", refreshToken)
        }
        return requestToken(config.tokenUrl, form)
    }

    /** Campos comuns a login e refresh — `client_secret` só entra se configurado (client confidencial). */
    private fun baseForm(config: KeycloakAuthProperties): MultiValueMap<String, String> =
        LinkedMultiValueMap<String, String>().apply {
            add("client_id", config.clientId)
            if (config.clientSecret.isNotBlank()) {
                add("client_secret", config.clientSecret)
            }
            add("scope", config.scope)
        }

    private fun requestToken(tokenUrl: String, form: MultiValueMap<String, String>): KeycloakTokenResponse =
        restClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(KeycloakTokenResponse::class.java)
            ?: error("Token endpoint do Keycloak ($tokenUrl) retornou corpo vazio")

    private fun store(response: KeycloakTokenResponse) {
        val now = Instant.now(clock)
        accessToken = response.accessToken
        accessExpiry = now.plusSeconds(response.expiresIn)
        // Rotação: substitui o refresh token só se o Keycloak devolver um novo (nem sempre devolve).
        response.refreshToken?.let {
            refreshToken = it
            refreshExpiry = now.plusSeconds(response.refreshExpiresIn)
        }
        logger.debug(
            "Token Keycloak atualizado: access expira em {}s, refresh em {}s",
            response.expiresIn,
            response.refreshExpiresIn,
        )
    }
}
