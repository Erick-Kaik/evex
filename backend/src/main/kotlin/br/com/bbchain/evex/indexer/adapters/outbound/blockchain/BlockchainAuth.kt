package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties

private const val AUTHORIZATION_HEADER = "Authorization"

/**
 * Header de autenticação estático do RPC (E27/DEC-094) — função pura, sem dependência de HTTP/WebSocket,
 * cobre só o caso do PAT/Bearer fixo (`authToken`/`authEnabled`). Regra: token presente **e**
 * `authEnabled != false` — não é um OR puro (com `authEnabled` default `true`, um OR faria
 * `authEnabled=true`+token vazio mandar `Authorization: Bearer ` quebrado). `authToken` vazio (default)
 * preserva 100% o comportamento sem auth de hoje — nenhuma rede sem auth (Hardhat local, `local-env`,
 * testes) é afetada.
 *
 * Desde o complemento Keycloak (E27/DEC-095), esta função não é mais chamada diretamente pelos
 * clientes de transporte — [resolveAuthHeaders] decide entre este PAT estático e o token dinâmico do
 * Keycloak, e é essa a função que [Web3jBlockchainClient]/[WebSocketPollTrigger] efetivamente usam.
 * Mantida pública/testada isoladamente (`BlockchainAuthTest`) por ser a peça mais simples de auditar.
 */
internal fun BlockchainProperties.authHeaders(): Map<String, String> =
    if (authEnabled && authToken.isNotBlank()) mapOf(AUTHORIZATION_HEADER to "Bearer $authToken") else emptyMap()

/**
 * Resolve o header de autenticação do RPC com precedência **Keycloak > PAT estático > sem auth**
 * (E27 complemento/DEC-095): se `indexer.blockchain.keycloak.token-url` estiver preenchido, o header
 * vem de [KeycloakTokenProvider.authorizationHeader] (token dinâmico, renovado a cada chamada); senão,
 * cai no PAT estático de [authHeaders] (DEC-094); senão, sem auth. Mesma filosofia "vazio preserva o
 * comportamento anterior" já usada no resto do projeto — não existe um enum `AUTH_MODE` explícito, a
 * presença de `token-url` já decide.
 *
 * Chamada a cada requisição real (não só na construção do cliente) — um token Keycloak expira em
 * minutos, então "calcular uma vez no construtor" não bastaria. [KeycloakTokenProvider] decide
 * internamente se reusa o token em cache, faz refresh, ou login completo; esta função não tem
 * conhecimento disso, só decide QUAL fonte de header consultar.
 */
internal fun resolveAuthHeaders(
    properties: BlockchainProperties,
    keycloakTokenProvider: KeycloakTokenProvider,
): Map<String, String> =
    if (properties.keycloak.tokenUrl.isNotBlank()) {
        mapOf(AUTHORIZATION_HEADER to keycloakTokenProvider.authorizationHeader())
    } else {
        properties.authHeaders()
    }
