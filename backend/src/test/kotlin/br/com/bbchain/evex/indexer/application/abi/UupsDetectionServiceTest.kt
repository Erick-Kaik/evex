package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiEntryKind
import br.com.bbchain.evex.indexer.domain.abi.CatalogedAbiEntry
import br.com.bbchain.evex.indexer.domain.abi.CatalogedAbiEntryParam
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit test — [ContractAbiEntryCatalogService] mockado (MockK, DEC-009), o mesmo racional de
 * [ContractAbiEntryCatalogServiceTest] pra quem ele delega: este teste programa contra a composição
 * "catálogo + filtro de versão vigente + detector puro", não reimplementa a cobertura de 404/versionamento
 * já coberta lá.
 */
class UupsDetectionServiceTest {

    private val contractAbiEntryCatalogService: ContractAbiEntryCatalogService = mockk()
    private val service = UupsDetectionService(contractAbiEntryCatalogService)

    private val address = "0xaaaa0000000000000000000000000000000aaaaa"

    private fun function(
        name: String,
        inputs: List<CatalogedAbiEntryParam>,
        validFromBlock: Long,
        validToBlock: Long?,
    ): CatalogedAbiEntry = CatalogedAbiEntry(
        kind = AbiEntryKind.FUNCTION,
        name = name,
        stateMutability = "nonpayable",
        inputs = inputs,
        outputs = emptyList(),
        anonymous = null,
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
    )

    @Test
    fun `propagates ContractNotFoundException from the underlying catalog`() {
        every { contractAbiEntryCatalogService.catalogFor(address) } throws ContractNotFoundException(address)

        assertThatThrownBy { service.detect(address) }
            .isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `no ABI registered at all yields isUpgradeable false, not an error`() {
        every { contractAbiEntryCatalogService.catalogFor(address) } returns emptyList()

        val result = service.detect(address)

        assertThat(result.isUpgradeable).isFalse()
        assertThat(result.availableUpgradeMethods).isEmpty()
    }

    @Test
    fun `only the current version (validToBlock null) is considered - an old upgradeable version does not leak in`() {
        val oldProxiableUuid = function(name = "proxiableUUID", inputs = emptyList(), validFromBlock = 0, validToBlock = 99)
        val currentBalanceOf = function(
            name = "balanceOf",
            inputs = listOf(CatalogedAbiEntryParam(name = "owner", type = "address")),
            validFromBlock = 100,
            validToBlock = null,
        )
        every { contractAbiEntryCatalogService.catalogFor(address) } returns listOf(oldProxiableUuid, currentBalanceOf)

        val result = service.detect(address)

        assertThat(result.isUpgradeable).isFalse()
        assertThat(result.availableUpgradeMethods).isEmpty()
    }

    @Test
    fun `the current version being upgradeable is reported even with a non-upgradeable old version`() {
        val oldBalanceOf = function(
            name = "balanceOf",
            inputs = listOf(CatalogedAbiEntryParam(name = "owner", type = "address")),
            validFromBlock = 0,
            validToBlock = 99,
        )
        val currentUpgradeTo = function(
            name = "upgradeTo",
            inputs = listOf(CatalogedAbiEntryParam(name = "newImplementation", type = "address")),
            validFromBlock = 100,
            validToBlock = null,
        )
        every { contractAbiEntryCatalogService.catalogFor(address) } returns listOf(oldBalanceOf, currentUpgradeTo)

        val result = service.detect(address)

        assertThat(result.isUpgradeable).isTrue()
        assertThat(result.availableUpgradeMethods).containsExactly("upgradeTo(address)")
    }
}
