package br.com.bbchain.evex.indexer.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.blockchain.*` (`application.yml`) — conexão com o nó, janela do poller e auto-shrink
 * (`ingestion-pipeline-reference.md` §1/§2). Mesmo padrão de [AbiStorageProperties] (`@Component`,
 * sem `@EnableConfigurationProperties` separado — pego pelo component scan padrão da raiz do pacote).
 */
@Component
@ConfigurationProperties(prefix = "indexer.blockchain")
class BlockchainProperties {
    /** URL do `HttpService` do Web3j — sem BBChain Keeper (regra não-negociável). */
    var rpcUrl: String = "http://localhost:8545"

    /** Quantos blocos de "cauda" ficam de fora do `safeHead` por segurança de reorg (DEC-005). */
    var confirmations: Long = 0

    /** Tamanho máximo (em blocos) da janela processada por tick. */
    var batchSize: Long = 1000

    /** Piso do auto-shrink — abaixo disso, erro de range sobe cru em vez de dividir mais. */
    var minBatchSize: Long = 1

    /** Intervalo entre ticks do `@Scheduled` (só usado quando [transport] é [BlockchainTransport.POLLING]). */
    var pollIntervalMs: Long = 5000

    /**
     * Qual gatilho dispara a ingestão — `POLLING` (default, `@Scheduled` de intervalo fixo, E4/E12) ou
     * `WEBSOCKET` (`eth_subscribe("newHeads")`, E26/DEC-091). `NONE` desliga os dois — usado só em
     * teste (`AbstractIntegrationTest`), substitui a antiga property `polling-enabled` (DEC-091).
     * Default `POLLING` preserva 100% o comportamento anterior a esta property existir.
     */
    var transport: BlockchainTransport = BlockchainTransport.POLLING

    /**
     * URL do `WebSocketService` do Web3j — só usada quando [transport] é [BlockchainTransport.WEBSOCKET].
     * Instância de `Web3j` **separada** da usada por [br.com.bbchain.evex.indexer.adapters.outbound
     * .blockchain.Web3jBlockchainClient] (essa continua 100% HTTP, DEC-091) — só pra assinar
     * `newHeadsNotifications()` e disparar o mesmo `PollTickService.tick()` do polling.
     */
    var wsUrl: String = "ws://localhost:8546"

    /**
     * PAT/Bearer opcional pra autenticar no RPC (ex.: rede Besu de teste da EBS, E27) — enviado como
     * header `Authorization: Bearer <token>` tanto na conexão HTTP ([Web3jBlockchainClient]) quanto na
     * WebSocket ([WebSocketPollTrigger], via handshake). Vazio (default) preserva 100% o comportamento
     * sem auth de hoje — nenhuma rede que já funciona sem auth (Hardhat local, `local-env`, testes) é
     * afetada. Ver [BlockchainAuth]/DEC-094.
     */
    var authToken: String = ""

    /**
     * Liga/desliga o envio do header sem precisar remover [authToken] do ambiente — útil quando o
     * secret já está setado numa plataforma de deploy e só se quer testar sem auth por um momento.
     * Default `true` de propósito: o caso comum é só setar `authToken` já bastar pra ativar o auth, sem
     * precisar mexer nesta flag também (DEC-094).
     */
    var authEnabled: Boolean = true

    /**
     * `indexer.blockchain.keycloak.*` — obtenção **dinâmica** de token via Keycloak
     * (`grant_type=password`), pra redes que exigem login de verdade em vez de um PAT estático colado
     * no ambiente (ex.: rede Besu real da EBS, E27 complemento/DEC-095). Reaproveita o padrão do
     * `ServiceTokenProvider` do código base Anbima (login/refresh/skew/lock), com `RestClient` no lugar
     * de `WebClient` (o projeto não tem WebFlux no classpath).
     *
     * Precedência resolvida em [br.com.bbchain.evex.indexer.adapters.outbound.blockchain
     * .resolveAuthHeaders]: [KeycloakAuthProperties.tokenUrl] preenchido → Keycloak; senão →
     * [authToken]/[authEnabled] (PAT estático, DEC-094); senão → sem auth. `tokenUrl` vazio (default)
     * preserva 100% o comportamento anterior a esta property existir.
     */
    var keycloak: KeycloakAuthProperties = KeycloakAuthProperties()
}

/**
 * Config do modo de auth via Keycloak (`indexer.blockchain.keycloak.*`) — ver [BlockchainProperties.keycloak].
 * Classe própria (não `data class`) só por consistência com [BlockchainProperties] (binding via `var`
 * mutável, sem necessidade de `copy()`/`equals()` estrutural).
 */
class KeycloakAuthProperties {
    /**
     * Token endpoint do Keycloak (ex.: `https://<host>/realms/<realm>/protocol/openid-connect/token`).
     * Vazio (default) desliga o modo Keycloak inteiro — cai no PAT estático (`authToken`/`authEnabled`).
     */
    var tokenUrl: String = ""

    /**
     * Client id do Keycloak. Sem default fixo de propósito — o Anbima usava um client público
     * (`login-app`, sem `client_secret`) como referência, mas cada rede real tem o seu; o operador
     * informa explicitamente via ambiente.
     */
    var clientId: String = ""

    /**
     * Client secret, opcional — só entra no form de login/refresh se não-branco. Cobre client
     * confidencial além do público (diferença em relação ao Anbima, que só tinha client público).
     */
    var clientSecret: String = ""

    /** Usuário de serviço usado no `grant_type=password`. */
    var username: String = ""

    /** Senha do usuário de serviço. */
    var password: String = ""

    /** `scope` enviado no login/refresh — mesmo default do Anbima. */
    var scope: String = "openid profile email"

    /**
     * Margem de segurança antes do access token expirar de verdade — [KeycloakTokenProvider] renova
     * (refresh ou login completo) um pouco antes do `expires_in` reportado pelo Keycloak, não em cima
     * da hora. Mesmo default do Anbima (`ServiceTokenProvider`).
     */
    var refreshSkew: Duration = Duration.ofSeconds(30)
}
