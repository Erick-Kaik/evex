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
 * End-to-end test through the real API (generated controller -> [ContractsApiServiceImpl] ->
 * [br.com.bbchain.evex.indexer.application.abi.ContractAbiEntryCatalogService] -> real Postgres via
 * Testcontainers) — DEC-009, mirror of `ContractMethodsApiTest`/`ContractEventCatalogApiTest` (E24).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ContractAbiEntriesApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private val mixedAbi = """
        [
          {
            "type": "constructor",
            "stateMutability": "nonpayable",
            "inputs": [{"name": "owner", "type": "address"}]
          },
          {
            "type": "function",
            "name": "balanceOf",
            "stateMutability": "view",
            "inputs": [{"name": "owner", "type": "address"}],
            "outputs": [{"name": "balance", "type": "uint256"}]
          },
          {
            "type": "function",
            "name": "transfer",
            "stateMutability": "nonpayable",
            "inputs": [{"name": "to", "type": "address"}, {"name": "amount", "type": "uint256"}],
            "outputs": [{"name": "", "type": "bool"}]
          },
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
            "type": "error",
            "name": "InsufficientBalance",
            "inputs": [{"name": "available", "type": "uint256"}, {"name": "required", "type": "uint256"}]
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

    private fun registerAbi(address: String, abi: String, validFromBlock: Long? = null) {
        val payload = buildMap {
            put("abi", abi)
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
    fun `GET returns every ABI item - function, event, error and constructor`() {
        val address = "0x111111111111111111111111111111111111111a"
        registerContract(address)
        registerAbi(address, mixedAbi)

        mockMvc.perform(get("/v1/contracts/$address/abi/entries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].kind").value("CONSTRUCTOR"))
            .andExpect(jsonPath("$[0].name").doesNotExist())
            .andExpect(jsonPath("$[0].inputs[0].name").value("owner"))
            .andExpect(jsonPath("$[1].kind").value("FUNCTION"))
            .andExpect(jsonPath("$[1].name").value("balanceOf"))
            .andExpect(jsonPath("$[1].stateMutability").value("view"))
            .andExpect(jsonPath("$[3].kind").value("EVENT"))
            .andExpect(jsonPath("$[3].name").value("Transfer"))
            .andExpect(jsonPath("$[3].inputs[0].indexed").value(true))
            .andExpect(jsonPath("$[4].kind").value("ERROR"))
            .andExpect(jsonPath("$[4].name").value("InsufficientBalance"))
            .andExpect(jsonPath("$[4].outputs.length()").value(0))
    }

    @Test
    fun `GET covers the proxy scenario - same entries repeated across two block ranges`() {
        val address = "0x222222222222222222222222222222222222222b"
        registerContract(address)
        registerAbi(address, mixedAbi)
        registerAbi(address, mixedAbi, validFromBlock = 100)

        mockMvc.perform(get("/v1/contracts/$address/abi/entries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$[0].validFromBlock").value(0))
            .andExpect(jsonPath("$[0].validToBlock").value(99))
            .andExpect(jsonPath("$[5].validFromBlock").value(100))
            .andExpect(jsonPath("$[5].validToBlock").doesNotExist())
    }

    @Test
    fun `GET returns an empty list (not 404) for a contract with no ABI registered`() {
        val address = "0x333333333333333333333333333333333333333c"
        registerContract(address)

        mockMvc.perform(get("/v1/contracts/$address/abi/entries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `GET for an unregistered contract returns 404`() {
        mockMvc.perform(get("/v1/contracts/0x999999999999999999999999999999999999999f/abi/entries"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }
}
