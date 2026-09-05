package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end test através da API real (controller gerado -> [ContractsApiServiceImpl] -> `application`
 * -> Postgres real via Testcontainers) — DEC-009, mesmo padrão de [ContractAbiApiTest].
 * `ObjectMapper` local (não `@Autowired`) — DEC-020.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ContractEventCatalogApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private val erc20Abi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          },
          {
            "type": "event",
            "name": "Approval",
            "inputs": [
              {"name": "owner", "type": "address", "indexed": true},
              {"name": "spender", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private fun registerContract(address: String) {
        val payload = mapOf("address" to address, "name" to "Token", "network" to "TESTNET")
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    private fun registerAbi(address: String, validFromBlock: Long? = null) {
        val payload = buildMap {
            put("abi", erc20Abi)
            put("source", "UPLOAD_TEXT")
            if (validFromBlock != null) put("validFromBlock", validFromBlock)
        }
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `GET events returns the decoded event catalog for a contract with an ABI`() {
        val address = "0x111111111111111111111111111111111111111a"
        registerContract(address)
        registerAbi(address)

        // Ordem: versões por validFromBlock asc; eventos na ordem de declaração do ABI (Transfer, Approval).
        mockMvc.perform(get("/v1/contracts/$address/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("Transfer"))
            .andExpect(jsonPath("$[0].validFromBlock").value(0))
            .andExpect(jsonPath("$[0].validToBlock").doesNotExist())
            .andExpect(jsonPath("$[0].inputs.length()").value(3))
            .andExpect(jsonPath("$[0].inputs[0].name").value("from"))
            .andExpect(jsonPath("$[0].inputs[0].type").value("address"))
            .andExpect(jsonPath("$[0].inputs[0].indexed").value(true))
            .andExpect(jsonPath("$[0].inputs[2].name").value("value"))
            .andExpect(jsonPath("$[0].inputs[2].indexed").value(false))
            .andExpect(jsonPath("$[1].name").value("Approval"))
    }

    @Test
    fun `GET events covers the proxy scenario - same event twice across two block ranges`() {
        val address = "0x222222222222222222222222222222222222222b"
        registerContract(address)
        registerAbi(address)
        registerAbi(address, validFromBlock = 100)

        // 2 versões x 2 eventos = 4 itens; ordem: [Transfer 0-99, Approval 0-99, Transfer 100-*, Approval 100-*].
        mockMvc.perform(get("/v1/contracts/$address/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(4))
            .andExpect(jsonPath("$[0].name").value("Transfer"))
            .andExpect(jsonPath("$[0].validFromBlock").value(0))
            .andExpect(jsonPath("$[0].validToBlock").value(99))
            .andExpect(jsonPath("$[2].name").value("Transfer"))
            .andExpect(jsonPath("$[2].validFromBlock").value(100))
            .andExpect(jsonPath("$[2].validToBlock").doesNotExist())
    }

    @Test
    fun `GET events returns an empty list for a contract with no ABI`() {
        val address = "0x333333333333333333333333333333333333333c"
        registerContract(address)

        mockMvc.perform(get("/v1/contracts/$address/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `GET events returns 404 for a valid but unregistered address`() {
        mockMvc.perform(get("/v1/contracts/0x999999999999999999999999999999999999999f/events"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }
}
