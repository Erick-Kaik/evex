package br.com.bbchain.evex.indexer.adapters.outbound.discovery

import br.com.bbchain.evex.indexer.config.BlockscoutProperties
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractInfo
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractSummary
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeHistory
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedEvent
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Clock
import java.time.Instant

/**
 * Cliente Blockscout (`BlockscoutPort`, doc de produto §7) — Spring `RestClient` (decisão travada do
 * projeto pra HTTP client, `backend/CLAUDE.md`), os 3 endpoints do doc: `GET /api/v2/smart-contracts`
 * (discovery — só retorna contratos verificados por padrão, sem precisar de `filter`), `GET /api/v2/
 * smart-contracts/{address}` (ABI, origem `BLOCKSCOUT` do cadastro), `GET /api/v2/addresses/{address}/
 * logs` (fallback de `topic0`s pra detecção automática de interface).
 *
 * Se `indexer.blockscout.base-url` estiver vazio, lança [BlockscoutUnavailableException] direto, sem
 * tentar HTTP — mesmo espírito de resiliência do doc §4.8 pra storage externo: Blockscout nunca pode
 * ser dependência crítica do caminho principal. Os 3 endpoints foram **validados contra uma instância
 * Blockscout real** (v11.2.8, compilada do source em `local-env/blockscout/`, ver `docs/decisions.md`
 * DEC-056/DEC-057 — a imagem publicada tem um bug conhecido não relacionado, corrigido compilando do
 * source) — `listVerifiedContracts`/`getAbi`/`getLogTopic0s` todos confirmados retornando dado real, não
 * só fabricado via `MockRestServiceServer` como antes de E12.
 *
 * `JsonNode`/`ObjectMapper` local (Jackson 2, não injetado — DEC-020), mesmo padrão de navegação
 * dinâmica já usado em `AbiParser` (E3): não modela DTOs fixos pros campos que não usamos.
 */
@Component
class BlockscoutClient(
    restClientBuilder: RestClient.Builder,
    private val properties: BlockscoutProperties,
    private val clock: Clock = Clock.systemUTC(),
    private val requestFactory: ClientHttpRequestFactory? = null,
) : BlockscoutPort {

    /**
     * Construtor usado pelo Spring (`@Autowired`): com [applyConfiguredTimeouts] aplica timeouts de
     * conexão/leitura (`indexer.blockscout.connect-timeout`/`read-timeout`) a TODO o cliente via
     * `JdkClientHttpRequestFactory`. Os testes usam o construtor primário (2 args), que não troca a fábrica
     * de requisições e portanto não sobrescreve o `MockRestServiceServer` ligado ao builder.
     */
    @Autowired
    constructor(restClientBuilder: RestClient.Builder, properties: BlockscoutProperties, @Value("#{true}") applyConfiguredTimeouts: Boolean) : this(
        restClientBuilder,
        properties,
        Clock.systemUTC(),
        if (applyConfiguredTimeouts) {
            // Redirect.NORMAL: o HttpClient cru segue NEVER por padrão e quebraria um Blockscout atrás de
            // redirect http→https ou de barra final (viraria 503); o builder do Boot segue redirects.
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(properties.connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build(),
            ).apply {
                setReadTimeout(properties.readTimeout)
            }
        } else {
            null
        },
    )

    private class CachedUpgradeLogs(val at: Instant, val value: BlockscoutUpgradeHistory)

    private val upgradeLogsLock = Any()

    @Volatile
    private var upgradeLogsCache: CachedUpgradeLogs? = null

    private val objectMapper = ObjectMapper()
    private val log = LoggerFactory.getLogger(BlockscoutClient::class.java)

    // Lazy: só constrói o RestClient (e valida a base-url) na primeira chamada, não no boot — evita
    // qualquer custo/validação de config quando Blockscout nunca é usado nesta instância.
    //
    // Força explicitamente o conversor JSON pra MappingJackson2HttpMessageConverter (Jackson 2) — sem
    // isso, o RestClient.Builder (tanto o bean autoconfigurado quanto um builder "cru" de teste) pode
    // resolver o corpo via o conversor JSON nativo do Spring Framework 7 (Jackson 3, `tools.jackson` —
    // mesma raiz da DEC-020) e falhar tentando desserializar pro tipo Jackson 2 `com.fasterxml.jackson.
    // databind.JsonNode` usado por este cliente (consistência com o resto do projeto, que é Jackson 2
    // em todo lugar). `@Suppress`: a classe Jackson 2 em si é `@Deprecated` no Framework 7 (o padrão
    // agora é Jackson 3), mas usá-la aqui é intencional, não um descuido — descoberto ao rodar
    // `BlockscoutClientTest` (`InvalidDefinitionException` do lado Jackson 3).
    @Suppress("DEPRECATION")
    private val restClient: RestClient? by lazy {
        properties.baseUrl.takeIf { it.isNotBlank() }?.let { baseUrl ->
            restClientBuilder
                .baseUrl(baseUrl)
                .also { b -> requestFactory?.let { b.requestFactory(it) } }
                .configureMessageConverters { it.withJsonConverter(MappingJackson2HttpMessageConverter()) }
                .build()
        }
    }

    override fun listVerifiedContracts(): List<BlockscoutContractSummary> {
        // Sem `?filter=verified`: confirmado contra uma instância Blockscout real (v11.2.8, local-env/
        // blockscout/, ver docs/decisions.md DEC-057) que `GET /api/v2/smart-contracts` já só retorna
        // contratos verificados por padrão — `filter` hoje é só pra restringir por linguagem
        // (solidity/vyper/yul), não por status de verificação; `filter=verified` é valor inválido pro
        // enum atual e a chamada falharia (422) contra uma instância de verdade.
        // Pagina seguindo `next_page_params` (padrão Blockscout v2): todos os pares viram query params da
        // próxima requisição. Para em: ausência/nulo de next_page_params, laço (mesmos params repetidos)
        // ou teto de páginas (WARN sem dados sensíveis, sem lançar). Falha em qualquer página propaga
        // BlockscoutUnavailableException — nunca devolve lista parcial silenciosa.
        val result = LinkedHashMap<String, BlockscoutContractSummary>()
        val seenParams = HashSet<Map<String, String>>()
        var params: Map<String, String> = emptyMap()
        var pages = 0
        while (true) {
            val root = getPage("/api/v2/smart-contracts", params)
            pages++
            root.path("items").forEach { item ->
                // "address" é um objeto aninhado ({hash, name, is_verified, ...}), não uma string plana —
                // confirmado contra instância real (v11.2.8, DEC-057).
                val addressNode = item.path("address")
                val address = addressNode.path("hash").asText()
                result.putIfAbsent(
                    address.lowercase(),
                    BlockscoutContractSummary(address = address, name = addressNode.path("name").asText(null)),
                )
            }
            val next = root.path("next_page_params")
            if (!next.isObject || next.isEmpty) break
            val nextParams = LinkedHashMap<String, String>()
            next.fields().forEach { (k, v) -> if (!v.isNull) nextParams[k] = v.asText() }
            if (nextParams.isEmpty() || !seenParams.add(nextParams)) {
                log.warn("Blockscout: paginação de contratos interrompida (params de próxima página repetidos) após {} página(s)", pages)
                break
            }
            if (pages >= MAX_DISCOVERY_PAGES) {
                log.warn("Blockscout: teto de {} páginas atingido na listagem de contratos; resultado possivelmente incompleto", MAX_DISCOVERY_PAGES)
                break
            }
            params = nextParams
        }
        return result.values.toList()
    }

    override fun getAbi(contractAddress: String): String? = getContract(contractAddress)?.abi

    override fun getContract(address: String): BlockscoutContractInfo? {
        val client = requireClient()
        val root = try {
            client.get().uri("/api/v2/smart-contracts/{address}", address)
                .retrieve()
                .body(JsonNode::class.java)
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.value() == 404) {
                return null
            }
            throw BlockscoutUnavailableException("Blockscout indisponível ao consultar contrato $address (${ex.javaClass.simpleName})", ex)
        } catch (ex: RestClientException) {
            throw BlockscoutUnavailableException("Blockscout indisponível ao consultar contrato $address (${ex.javaClass.simpleName})", ex)
        } ?: return null
        val abiNode = root.path("abi")
        val abi = if (abiNode.isMissingNode || abiNode.isNull) null else objectMapper.writeValueAsString(abiNode)
        return BlockscoutContractInfo(
            name = root.path("name").asText(null),
            abi = abi,
            proxyType = root.path("proxy_type").asText(null),
            implementations = implementationsOf(root),
        )
    }

    override fun getCreationBlock(address: String): Long? {
        val addressNode = getOrNull("/api/v2/addresses/{address}", address) ?: return null
        val txHash = addressNode.path("creation_transaction_hash").asText(null)?.takeIf { it.isNotBlank() } ?: return null
        val tx = getOrNull("/api/v2/transactions/{hash}", txHash) ?: return null
        val blockNode = tx.path("block_number").takeUnless { it.isMissingNode || it.isNull } ?: tx.path("block")
        return when {
            blockNode.isIntegralNumber -> blockNode.asLong().takeIf { it >= 0 }
            blockNode.isTextual -> parseNumber(blockNode.asText())?.takeIf { it >= 0 }
            else -> null
        }
    }

    /** GET que devolve `null` em 404 ou corpo vazio; demais falhas => [BlockscoutUnavailableException] (só path + tipo). */
    private fun getOrNull(path: String, vararg uriVariables: Any): JsonNode? {
        val client = requireClient()
        return try {
            client.get().uri(path, *uriVariables).retrieve().body(JsonNode::class.java)
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.value() == 404) return null
            throw BlockscoutUnavailableException("Blockscout indisponível ($path, ${ex.javaClass.simpleName})", ex)
        } catch (ex: RestClientException) {
            throw BlockscoutUnavailableException("Blockscout indisponível ($path, ${ex.javaClass.simpleName})", ex)
        }
    }

    /**
     * Lê `implementations[]` (`address_hash` na v2 atual; `address` por tolerância); cai para o campo
     * legado `implementation_address` se o array não existir.
     */
    private fun implementationsOf(root: JsonNode): List<String> {
        val fromArray = root.path("implementations").mapNotNull { node ->
            (node.path("address_hash").asText(null) ?: node.path("address").asText(null))?.takeIf { it.isNotBlank() }
        }
        if (fromArray.isNotEmpty()) return fromArray
        return listOfNotNull(root.path("implementation_address").asText(null)?.takeIf { it.isNotBlank() })
    }

    override fun getUpgradeHistory(proxy: String): BlockscoutUpgradeHistory {
        val root = get(
            "/api?module=logs&action=getLogs&address={address}&topic0={topic0}&fromBlock=0&toBlock=latest",
            proxy,
            UpgradedEvent.TOPIC0,
        )
        val logs = parseLogs(root, "para $proxy", withEmitter = false)
        return BlockscoutUpgradeHistory(logs.entries, truncated = logs.rawSize >= UPGRADE_HISTORY_LIMIT)
    }

    private class ParsedLogs(val entries: List<BlockscoutUpgradeLog>, val rawSize: Int)

    /**
     * Interpreta a resposta de `getLogs`. "No logs found" (status 0, resultado não-array) => vazio;
     * qualquer outro resultado textual = erro => [BlockscoutUnavailableException]. Com [withEmitter]
     * preenche `proxyAddress` com o campo `address` do log (lowercase); logs sem emissor são ignorados.
     */
    private fun parseLogs(root: JsonNode, context: String, withEmitter: Boolean): ParsedLogs {
        val result = root.path("result")
        if (!result.isArray) {
            if (result.isTextual && root.path("message").asText("").contains("No logs found", ignoreCase = true)) {
                return ParsedLogs(emptyList(), 0)
            }
            throw BlockscoutUnavailableException(
                "Blockscout retornou erro em getLogs $context: ${root.path("message").asText("")} ${result.asText("")}".trim(),
            )
        }
        val entries = result.mapNotNull { log ->
            val impl = UpgradedEvent.addressFromTopic(log.path("topics").path(1).asText(null)) ?: return@mapNotNull null
            val block = parseNumber(log.path("blockNumber").asText(null)) ?: return@mapNotNull null
            val txHash = log.path("transactionHash").asText(null) ?: return@mapNotNull null
            val emitter = if (withEmitter) {
                log.path("address").asText(null)?.takeIf { it.isNotBlank() }?.lowercase() ?: return@mapNotNull null
            } else {
                null
            }
            BlockscoutUpgradeLog(
                implementationAddress = impl,
                blockNumber = block,
                logIndex = parseNumber(log.path("logIndex").asText(null)),
                txHash = txHash,
                proxyAddress = emitter,
            )
        }
        return ParsedLogs(entries, result.size())
    }

    override fun getAllUpgradeLogs(): BlockscoutUpgradeHistory {
        val ttl = properties.upgradeLogsCacheTtl
        if (ttl.isZero || ttl.isNegative) return fetchAllUpgradeLogs()
        freshCache(ttl)?.let { return it }
        // Single-flight: só uma thread refaz; as demais esperam e reaproveitam o resultado novo.
        synchronized(upgradeLogsLock) {
            freshCache(ttl)?.let { return it }
            val value = fetchAllUpgradeLogs() // falha propaga e NÃO é cacheada
            upgradeLogsCache = CachedUpgradeLogs(clock.instant(), value)
            return value
        }
    }

    private fun freshCache(ttl: java.time.Duration): BlockscoutUpgradeHistory? {
        val cached = upgradeLogsCache ?: return null
        return cached.value.takeIf { clock.instant().isBefore(cached.at.plus(ttl)) }
    }

    /**
     * Pagina por bloco (a API ignora `page`/`offset`; devolve em ordem crescente e `fromBlock` filtra):
     * página cheia (≥ [UPGRADE_HISTORY_LIMIT]) => repete com `fromBlock` = maior bloco recebido
     * (inclusivo), deduplicando por (txHash, logIndex). Trunca se o bloco não avança ou no teto de páginas.
     */
    private fun fetchAllUpgradeLogs(): BlockscoutUpgradeHistory {
        val maxPages = properties.upgradeLogsMaxPages.coerceAtLeast(1)
        val all = LinkedHashMap<Pair<String, Long?>, BlockscoutUpgradeLog>()
        var fromBlock = 0L
        var pages = 0
        while (true) {
            val root = get(
                "/api?module=logs&action=getLogs&topic0={topic0}&fromBlock={fromBlock}&toBlock=latest",
                UpgradedEvent.TOPIC0,
                fromBlock,
            )
            val page = parseLogs(root, "(upgrade logs)", withEmitter = true)
            pages++
            page.entries.forEach { all.putIfAbsent(it.txHash to it.logIndex, it) }
            if (page.rawSize < UPGRADE_HISTORY_LIMIT) {
                return BlockscoutUpgradeHistory(all.values.toList(), truncated = false)
            }
            val maxBlock = page.entries.maxOfOrNull { it.blockNumber }
            if (maxBlock == null || maxBlock <= fromBlock) {
                log.warn("Blockscout: {} ou mais logs Upgraded num mesmo bloco; resultado marcado como truncado", UPGRADE_HISTORY_LIMIT)
                return BlockscoutUpgradeHistory(all.values.toList(), truncated = true)
            }
            if (pages >= maxPages) {
                log.warn("Blockscout: teto de {} páginas atingido em logs Upgraded; resultado marcado como truncado", maxPages)
                return BlockscoutUpgradeHistory(all.values.toList(), truncated = true)
            }
            fromBlock = maxBlock
        }
    }

    /** Aceita `0x`-hex (formato do Blockscout em getLogs) ou decimal; `null` se vazio/inválido. */
    private fun parseNumber(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("0x") || raw.startsWith("0X")) {
            raw.substring(2).takeIf { it.isNotEmpty() }?.toLongOrNull(16)
        } else {
            raw.toLongOrNull()
        }
    }

    override fun getLogTopic0s(contractAddress: String): Set<String> {
        val root = get("/api/v2/addresses/{address}/logs", contractAddress)
        return root.path("items").mapNotNull { item ->
            item.path("topics").firstOrNull()?.takeIf { it.isTextual }?.asText()
        }.toSet()
    }

    private companion object {
        /** Limite de resultados da API de logs do Blockscout; atingir => histórico possivelmente truncado. */
        const val UPGRADE_HISTORY_LIMIT = 1000

        /** Teto de páginas da listagem de contratos verificados (proteção contra paginação sem fim). */
        const val MAX_DISCOVERY_PAGES = 100
    }

    private fun requireClient(): RestClient =
        restClient ?: throw BlockscoutUnavailableException("Blockscout não configurado (indexer.blockscout.base-url vazio).")

    private fun get(path: String, vararg uriVariables: Any): JsonNode {
        val client = requireClient()
        return try {
            client.get().uri(path, *uriVariables).retrieve().body(JsonNode::class.java)
                ?: throw BlockscoutUnavailableException("Blockscout retornou corpo vazio para $path")
        } catch (ex: BlockscoutUnavailableException) {
            throw ex
        } catch (ex: RestClientException) {
            throw BlockscoutUnavailableException("Blockscout indisponível ($path, ${ex.javaClass.simpleName})", ex)
        }
    }

    /** GET com query params montados via uriBuilder + variáveis de template (encoding seguro, sem concatenar). */
    private fun getPage(path: String, params: Map<String, String>): JsonNode {
        val client = requireClient()
        return try {
            client.get().uri { b ->
                b.path(path)
                val vars = HashMap<String, Any>()
                params.entries.forEachIndexed { i, (k, v) ->
                    b.queryParam(k, "{p$i}")
                    vars["p$i"] = v
                }
                b.build(vars)
            }.retrieve().body(JsonNode::class.java)
                ?: throw BlockscoutUnavailableException("Blockscout retornou corpo vazio para $path")
        } catch (ex: BlockscoutUnavailableException) {
            throw ex
        } catch (ex: RestClientException) {
            throw BlockscoutUnavailableException("Blockscout indisponível ($path, ${ex.javaClass.simpleName})", ex)
        }
    }
}
