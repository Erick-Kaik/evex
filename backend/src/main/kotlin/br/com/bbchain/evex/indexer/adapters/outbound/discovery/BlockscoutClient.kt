package br.com.bbchain.evex.indexer.adapters.outbound.discovery

import br.com.bbchain.evex.indexer.config.BlockscoutProperties
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractSummary
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

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
) : BlockscoutPort {

    private val objectMapper = ObjectMapper()

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
        val root = get("/api/v2/smart-contracts")
        return root.path("items").map { item ->
            // "address" é um objeto aninhado ({hash, name, is_verified, ...}), não uma string plana —
            // confirmado contra instância real (v11.2.8, DEC-057); a suposição anterior (best-effort,
            // sem instância real disponível) estava errada nos dois campos.
            val addressNode = item.path("address")
            BlockscoutContractSummary(
                address = addressNode.path("hash").asText(),
                name = addressNode.path("name").asText(null),
            )
        }
    }

    override fun getAbi(contractAddress: String): String? {
        val client = requireClient()
        val root = try {
            client.get().uri("/api/v2/smart-contracts/{address}", contractAddress)
                .retrieve()
                .body(JsonNode::class.java)
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.value() == 404) {
                return null
            }
            throw BlockscoutUnavailableException("Falha ao consultar ABI no Blockscout para $contractAddress: ${ex.message}", ex)
        } catch (ex: RestClientException) {
            throw BlockscoutUnavailableException("Falha ao consultar ABI no Blockscout para $contractAddress: ${ex.message}", ex)
        }
        val abiNode = root?.path("abi")
        if (abiNode == null || abiNode.isMissingNode || abiNode.isNull) {
            return null
        }
        return objectMapper.writeValueAsString(abiNode)
    }

    override fun getLogTopic0s(contractAddress: String): Set<String> {
        val root = get("/api/v2/addresses/{address}/logs", contractAddress)
        return root.path("items").mapNotNull { item ->
            item.path("topics").firstOrNull()?.takeIf { it.isTextual }?.asText()
        }.toSet()
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
            throw BlockscoutUnavailableException("Falha ao consultar Blockscout ($path): ${ex.message}", ex)
        }
    }
}
