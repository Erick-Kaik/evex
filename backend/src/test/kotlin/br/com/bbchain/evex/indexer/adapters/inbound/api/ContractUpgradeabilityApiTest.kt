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
 * [br.com.bbchain.evex.indexer.application.abi.UupsDetectionService] -> real Postgres via
 * Testcontainers) — DEC-009, mirror of `ContractAbiEntriesApiTest` (E24). O ABI UUPS abaixo é o
 * subconjunto real do `UUPSUpgradeable` da OpenZeppelin usado pelo `TestUpgradeableVaultV1`/`V2` de
 * `local-env/` (E20) — `proxiableUUID()`/`upgradeTo(address)`/`upgradeToAndCall(address,bytes)`, além
 * de uma função de negócio comum (`deposit`) só pra provar que ela não interfere na detecção (E28).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ContractUpgradeabilityApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private val uupsAbi = """
        [
          {
            "type": "function",
            "name": "deposit",
            "stateMutability": "nonpayable",
            "inputs": [{"name": "amount", "type": "uint256"}],
            "outputs": []
          },
          {
            "type": "function",
            "name": "proxiableUUID",
            "stateMutability": "view",
            "inputs": [],
            "outputs": [{"name": "", "type": "bytes32"}]
          },
          {
            "type": "function",
            "name": "upgradeTo",
            "stateMutability": "nonpayable",
            "inputs": [{"name": "newImplementation", "type": "address"}],
            "outputs": []
          },
          {
            "type": "function",
            "name": "upgradeToAndCall",
            "stateMutability": "payable",
            "inputs": [{"name": "newImplementation", "type": "address"}, {"name": "data", "type": "bytes"}],
            "outputs": []
          }
        ]
    """.trimIndent()

    private val nonUpgradeableAbi = """
        [
          {
            "type": "function",
            "name": "balanceOf",
            "stateMutability": "view",
            "inputs": [{"name": "owner", "type": "address"}],
            "outputs": [{"name": "balance", "type": "uint256"}]
          }
        ]
    """.trimIndent()

    /** `upgradeTo(uint256)` — mesmo nome do UUPS, assinatura diferente. Não deve contar como match. */
    private val fakeUpgradeToAbi = """
        [
          {
            "type": "function",
            "name": "upgradeTo",
            "stateMutability": "nonpayable",
            "inputs": [{"name": "id", "type": "uint256"}],
            "outputs": []
          }
        ]
    """.trimIndent()

    private fun registerContract(address: String) {
        val payload = mapOf("address" to address, "name" to "Vault", "network" to "TESTNET")
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
    fun `a real UUPS ABI (TestUpgradeableVault) is reported upgradeable with both upgrade methods`() {
        val address = "0x111111111111111111111111111111111111111a"
        registerContract(address)
        registerAbi(address, uupsAbi)

        mockMvc.perform(get("/v1/contracts/$address/upgradeability"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isUpgradeable").value(true))
            .andExpect(jsonPath("$.availableUpgradeMethods.length()").value(2))
            .andExpect(jsonPath("$.availableUpgradeMethods[0]").value("upgradeTo(address)"))
            .andExpect(jsonPath("$.availableUpgradeMethods[1]").value("upgradeToAndCall(address,bytes)"))
    }

    @Test
    fun `a non-upgradeable ABI reports isUpgradeable false with an empty method list`() {
        val address = "0x222222222222222222222222222222222222222b"
        registerContract(address)
        registerAbi(address, nonUpgradeableAbi)

        mockMvc.perform(get("/v1/contracts/$address/upgradeability"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isUpgradeable").value(false))
            .andExpect(jsonPath("$.availableUpgradeMethods.length()").value(0))
    }

    @Test
    fun `a contract with no ABI registered returns 200 with isUpgradeable false, not an error`() {
        val address = "0x333333333333333333333333333333333333333c"
        registerContract(address)

        mockMvc.perform(get("/v1/contracts/$address/upgradeability"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isUpgradeable").value(false))
    }

    @Test
    fun `a same-named function with a different signature does not count as a match`() {
        val address = "0x444444444444444444444444444444444444444d"
        registerContract(address)
        registerAbi(address, fakeUpgradeToAbi)

        mockMvc.perform(get("/v1/contracts/$address/upgradeability"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isUpgradeable").value(false))
            .andExpect(jsonPath("$.availableUpgradeMethods.length()").value(0))
    }

    @Test
    fun `only the current ABI version is considered - an old upgradeable version does not leak in`() {
        val address = "0x555555555555555555555555555555555555555e"
        registerContract(address)
        registerAbi(address, uupsAbi)
        registerAbi(address, nonUpgradeableAbi, validFromBlock = 100)

        mockMvc.perform(get("/v1/contracts/$address/upgradeability"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isUpgradeable").value(false))
            .andExpect(jsonPath("$.availableUpgradeMethods.length()").value(0))
    }

    @Test
    fun `GET for an unregistered contract returns 404`() {
        mockMvc.perform(get("/v1/contracts/0x999999999999999999999999999999999999999f/upgradeability"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }
}
