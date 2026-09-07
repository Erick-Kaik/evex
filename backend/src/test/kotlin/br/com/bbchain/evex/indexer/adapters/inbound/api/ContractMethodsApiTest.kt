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
 * [br.com.bbchain.evex.indexer.application.abi.ContractMethodCatalogService] -> real Postgres via
 * Testcontainers) — DEC-009, mirror of `ContractAbiApiTest`/`ContractsApiTest`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ContractMethodsApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private val balanceOfAbi = """
        [
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

    private fun registerAbi(address: String, abi: String) {
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to abi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `GET returns only view-pure methods, non-payable functions are filtered out`() {
        val address = "0x111111111111111111111111111111111111111a"
        registerContract(address)
        registerAbi(address, balanceOfAbi)

        mockMvc.perform(get("/v1/contracts/$address/methods"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("balanceOf"))
            .andExpect(jsonPath("$[0].inputs[0].name").value("owner"))
            .andExpect(jsonPath("$[0].inputs[0].type").value("address"))
            .andExpect(jsonPath("$[0].outputs[0].name").value("balance"))
            .andExpect(jsonPath("$[0].outputs[0].type").value("uint256"))
    }

    @Test
    fun `GET returns an empty list (not 404) for a contract with no ABI registered`() {
        val address = "0x222222222222222222222222222222222222222b"
        registerContract(address)

        mockMvc.perform(get("/v1/contracts/$address/methods"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `GET for an unregistered contract returns 404`() {
        mockMvc.perform(get("/v1/contracts/0x999999999999999999999999999999999999999f/methods"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }
}
