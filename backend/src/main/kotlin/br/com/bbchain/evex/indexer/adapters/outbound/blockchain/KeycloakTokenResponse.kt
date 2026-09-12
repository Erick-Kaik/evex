package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Resposta padrão do token endpoint do Keycloak (login `grant_type=password` e `grant_type=
 * refresh_token`) — mesmo formato usado pelo `TokenResponse` do código base Anbima
 * (`br.com.bbchain.anbima.indexer.keeper.auth`), copiado aqui sem mudança de forma (só de pacote/nome,
 * pra não colidir com nenhum outro `TokenResponse` do Evex).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class KeycloakTokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("expires_in") val expiresIn: Long,
    @JsonProperty("refresh_token") val refreshToken: String? = null,
    @JsonProperty("refresh_expires_in") val refreshExpiresIn: Long = 0,
)
