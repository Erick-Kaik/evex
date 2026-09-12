package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * `MockRestServiceServer` (mesmo mecanismo já usado em `BlockscoutClientTest`/`WebhookHttpClientTest`)
 * — sem chamada real a Keycloak (E27 complemento/DEC-095). [MutableClock] permite avançar o "agora"
 * manualmente pra exercitar reuso/expiração/skew determinísticamente, sem `Thread.sleep`.
 */
class KeycloakTokenProviderTest {

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private data class Fixture(
        val provider: KeycloakTokenProvider,
        val mockServer: MockRestServiceServer,
        val clock: MutableClock,
    )

    private fun buildFixture(clientSecret: String = "", refreshSkew: Duration = Duration.ofSeconds(30)): Fixture {
        val builder = RestClient.builder()
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        val properties = BlockchainProperties().apply {
            keycloak.tokenUrl = TOKEN_URL
            keycloak.clientId = "evex-client"
            keycloak.clientSecret = clientSecret
            keycloak.username = "svc-user"
            keycloak.password = "svc-pass"
            keycloak.refreshSkew = refreshSkew
        }
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        return Fixture(KeycloakTokenProvider(properties, builder, clock), mockServer, clock)
    }

    private fun tokenJson(accessToken: String, expiresIn: Long, refreshToken: String, refreshExpiresIn: Long) =
        """{"access_token":"$accessToken","expires_in":$expiresIn,"refresh_token":"$refreshToken","refresh_expires_in":$refreshExpiresIn}"""

    @Test
    fun `login inicial obtém o token via grant_type=password`() {
        val (provider, mockServer) = buildFixture()
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(
                content().formDataContains(
                    mapOf(
                        "grant_type" to "password",
                        "client_id" to "evex-client",
                        "username" to "svc-user",
                        "password" to "svc-pass",
                        "scope" to "openid profile email",
                    ),
                ),
            )
            .andRespond(withSuccess(tokenJson("token-1", 300, "refresh-1", 1800), MediaType.APPLICATION_JSON))

        assertThat(provider.authorizationHeader()).isEqualTo("Bearer token-1")
        mockServer.verify()
    }

    @Test
    fun `reusa o token em cache enquanto ainda está válido, sem 2ª chamada ao Keycloak`() {
        val (provider, mockServer) = buildFixture()
        mockServer.expect(requestTo(TOKEN_URL))
            .andRespond(withSuccess(tokenJson("token-1", 300, "refresh-1", 1800), MediaType.APPLICATION_JSON))

        provider.authorizationHeader()
        val second = provider.authorizationHeader()

        assertThat(second).isEqualTo("Bearer token-1")
        mockServer.verify() // só 1 requisição esperada — falharia aqui se uma 2ª tivesse sido disparada
    }

    @Test
    fun `renova via refresh quando o access token está perto de expirar mas o refresh ainda vale`() {
        val (provider, mockServer, clock) = buildFixture()
        // MockRestServiceServer (manager default) exige todas as expectations registradas ANTES de
        // qualquer request real ser disparada — não dá pra intercalar `.expect()`/chamada real/`.expect()`.
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(content().formDataContains(mapOf("grant_type" to "password")))
            .andRespond(withSuccess(tokenJson("token-1", 60, "refresh-1", 1800), MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(content().formDataContains(mapOf("grant_type" to "refresh_token", "refresh_token" to "refresh-1")))
            .andRespond(withSuccess(tokenJson("token-2", 300, "refresh-2", 1800), MediaType.APPLICATION_JSON))

        provider.authorizationHeader() // 1ª chamada: login completo
        clock.advance(Duration.ofSeconds(35)) // resta 25s (< skew de 30s) — access não é mais "válido"
        assertThat(provider.authorizationHeader()).isEqualTo("Bearer token-2") // 2ª chamada: refresh

        mockServer.verify()
    }

    @Test
    fun `cai para login completo quando o refresh falha`() {
        val (provider, mockServer, clock) = buildFixture()
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(content().formDataContains(mapOf("grant_type" to "password")))
            .andRespond(withSuccess(tokenJson("token-1", 60, "refresh-1", 1800), MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(content().formDataContains(mapOf("grant_type" to "refresh_token")))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""{"error":"invalid_grant"}"""))
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(content().formDataContains(mapOf("grant_type" to "password")))
            .andRespond(withSuccess(tokenJson("token-3", 300, "refresh-3", 1800), MediaType.APPLICATION_JSON))

        provider.authorizationHeader() // 1ª chamada: login completo
        clock.advance(Duration.ofSeconds(35))
        assertThat(provider.authorizationHeader()).isEqualTo("Bearer token-3") // refresh falha, cai pra login

        mockServer.verify()
    }

    @Test
    fun `client_secret entra no form de login só quando configurado`() {
        val (provider, mockServer) = buildFixture(clientSecret = "s3cr3t")
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(content().formDataContains(mapOf("client_secret" to "s3cr3t")))
            .andRespond(withSuccess(tokenJson("token-1", 300, "refresh-1", 1800), MediaType.APPLICATION_JSON))

        provider.authorizationHeader()

        mockServer.verify()
    }

    @Test
    fun `sem client_secret configurado, o form não inclui o campo (client público, ex Anbima login-app)`() {
        val (provider, mockServer) = buildFixture(clientSecret = "")
        mockServer.expect(requestTo(TOKEN_URL))
            .andExpect(content().string(not(containsString("client_secret"))))
            .andRespond(withSuccess(tokenJson("token-1", 300, "refresh-1", 1800), MediaType.APPLICATION_JSON))

        provider.authorizationHeader()

        mockServer.verify()
    }

    companion object {
        private const val TOKEN_URL = "http://keycloak.test/realms/evex/protocol/openid-connect/token"
    }
}
