package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethodInput
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethodOutput
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiMethod
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentOutputMapping
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test — todas as portas mockadas via MockK (DEC-009). */
class WebhookEnrichmentResolverTest {

    private val enrichmentProfiles: EnrichmentProfilePort = mockk()
    private val monitoredContracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val abiMethodCatalog: AbiMethodCatalogPort = mockk()
    private val blockchain: BlockchainPort = mockk()

    private val resolver = WebhookEnrichmentResolver(enrichmentProfiles, monitoredContracts, contractAbis, abiContentReader, abiMethodCatalog, blockchain)

    private val targetAddress = "0xaaaa000000000000000000000000000000aaaa"

    private val monitoredContract = MonitoredContract(
        id = UUID.randomUUID(),
        address = targetAddress,
        name = "Token",
        description = null,
        version = null,
        network = Network.TESTNET,
        status = ContractStatus.ACTIVE,
        lineageId = null,
        createdAt = Instant.now(),
    )

    private val abi = ContractAbi(
        id = UUID.randomUUID(),
        contractAddress = targetAddress,
        network = Network.TESTNET,
        source = AbiSource.UPLOAD_TEXT,
        storageType = AbiStorageType.DATABASE,
        storageLocation = StorageLocation(abiContent = "irrelevant", storagePath = null),
        validFromBlock = 0,
        validToBlock = null,
        createdAt = Instant.now(),
    )

    private fun delivery(payload: String, blockNumber: Long? = 42, decodedParameters: Map<String, Any?>? = mapOf("to" to "0xdead")) = WebhookDelivery(
        id = UUID.randomUUID(),
        webhookConfigId = UUID.randomUUID(),
        blockchainEventId = UUID.randomUUID(),
        network = Network.TESTNET,
        payload = payload,
        status = WebhookDeliveryStatus.PENDING,
        attemptCount = 0,
        nextAttemptAt = null,
        createdAt = Instant.now(),
        blockNumber = blockNumber,
        decodedParameters = decodedParameters,
    )

    private fun stubAbiFor(contractAddress: String = targetAddress) {
        every { monitoredContracts.findByAddress(contractAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(contractAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns "irrelevant"
    }

    @Test
    fun `enrichmentProfileIds empty returns the payload untouched, never touches findAllByIds`() {
        val result = resolver.resolve(delivery("""{"a":1}"""), emptyList())

        assertThat(result).isEqualTo("""{"a":1}""")
        verify(exactly = 0) { enrichmentProfiles.findAllByIds(any()) }
    }

    @Test
    fun `resolves a single call and substitutes the enriched token`() {
        val profileId = UUID.randomUUID()
        val call = EnrichmentCall(
            targetContractAddress = targetAddress,
            methodName = "balanceOf",
            args = listOf("{{decodedParameters.to}}"),
            outputs = listOf(EnrichmentOutputMapping(0, "saldo")),
        )
        val profile = EnrichmentProfile(profileId, "Perfil", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, listOf(call), Instant.now())
        every { enrichmentProfiles.findAllByIds(listOf(profileId)) } returns listOf(profile)
        stubAbiFor()
        every { abiMethodCatalog.methodsOf("irrelevant") } returns listOf(
            ParsedAbiMethod("balanceOf", listOf(CatalogedMethodInput("owner", "address")), listOf(CatalogedMethodOutput("balance", "uint256"))),
        )
        every { blockchain.call(targetAddress, "balanceOf", listOf("address"), listOf("uint256"), listOf("0xdead"), CallBlock.Number(42)) } returns listOf("125000")

        val result = resolver.resolve(delivery("""{"saldo":{{enriched.saldo}}}"""), listOf(profileId))

        assertThat(result).isEqualTo("""{"saldo":"125000"}""")
    }

    @Test
    fun `chains a later call referencing the output of an earlier call in the same profile`() {
        val profileId = UUID.randomUUID()
        val firstCall = EnrichmentCall(targetAddress, "getOwner", listOf("{{decodedParameters.to}}"), listOf(EnrichmentOutputMapping(0, "owner")))
        val secondCall = EnrichmentCall(targetAddress, "balanceOf", listOf("{{enriched.owner}}"), listOf(EnrichmentOutputMapping(0, "saldo")))
        val profile = EnrichmentProfile(profileId, "Perfil", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, listOf(firstCall, secondCall), Instant.now())
        every { enrichmentProfiles.findAllByIds(listOf(profileId)) } returns listOf(profile)
        stubAbiFor()
        every { abiMethodCatalog.methodsOf("irrelevant") } returns listOf(
            ParsedAbiMethod("getOwner", listOf(CatalogedMethodInput("tokenId", "address")), listOf(CatalogedMethodOutput("owner", "address"))),
            ParsedAbiMethod("balanceOf", listOf(CatalogedMethodInput("owner", "address")), listOf(CatalogedMethodOutput("balance", "uint256"))),
        )
        every { blockchain.call(targetAddress, "getOwner", listOf("address"), listOf("address"), listOf("0xdead"), CallBlock.Number(42)) } returns listOf("0xowner")
        every { blockchain.call(targetAddress, "balanceOf", listOf("address"), listOf("uint256"), listOf("0xowner"), CallBlock.Number(42)) } returns listOf("999")

        val result = resolver.resolve(delivery("""{"saldo":{{enriched.saldo}}}"""), listOf(profileId))

        assertThat(result).isEqualTo("""{"saldo":"999"}""")
    }

    @Test
    fun `findAllByIds does not guarantee order - result is reordered by the original enrichmentProfileIds list`() {
        val profileAId = UUID.randomUUID()
        val profileBId = UUID.randomUUID()
        val callA = EnrichmentCall(targetAddress, "methodA", emptyList(), listOf(EnrichmentOutputMapping(0, "a")))
        val callB = EnrichmentCall(targetAddress, "methodB", listOf("{{enriched.a}}"), listOf(EnrichmentOutputMapping(0, "b")))
        val profileA = EnrichmentProfile(profileAId, "A", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, listOf(callA), Instant.now())
        val profileB = EnrichmentProfile(profileBId, "B", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, listOf(callB), Instant.now())
        // findAllByIds devolve na ordem "errada" (B antes de A) de propósito - a resolução precisa reordenar.
        every { enrichmentProfiles.findAllByIds(listOf(profileAId, profileBId)) } returns listOf(profileB, profileA)
        stubAbiFor()
        every { abiMethodCatalog.methodsOf("irrelevant") } returns listOf(
            ParsedAbiMethod("methodA", emptyList(), listOf(CatalogedMethodOutput("a", "uint256"))),
            ParsedAbiMethod("methodB", listOf(CatalogedMethodInput("a", "uint256")), listOf(CatalogedMethodOutput("b", "uint256"))),
        )
        every { blockchain.call(targetAddress, "methodA", emptyList(), listOf("uint256"), emptyList(), CallBlock.Number(42)) } returns listOf("111")
        every { blockchain.call(targetAddress, "methodB", listOf("uint256"), listOf("uint256"), listOf("111"), CallBlock.Number(42)) } returns listOf("222")

        val result = resolver.resolve(delivery("""{"a":{{enriched.a}},"b":{{enriched.b}}}"""), listOf(profileAId, profileBId))

        assertThat(result).isEqualTo("""{"a":"111","b":"222"}""")
    }

    @Test
    fun `one failing call does not derail the others - failed placeholders are left null, warns instead of throwing`() {
        val profileId = UUID.randomUUID()
        val brokenCall = EnrichmentCall(targetAddress, "unknownMethod", emptyList(), listOf(EnrichmentOutputMapping(0, "broken")))
        val goodCall = EnrichmentCall(targetAddress, "methodOk", emptyList(), listOf(EnrichmentOutputMapping(0, "ok")))
        val profile = EnrichmentProfile(profileId, "Perfil", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, listOf(brokenCall, goodCall), Instant.now())
        every { enrichmentProfiles.findAllByIds(listOf(profileId)) } returns listOf(profile)
        stubAbiFor()
        every { abiMethodCatalog.methodsOf("irrelevant") } returns listOf(
            ParsedAbiMethod("methodOk", emptyList(), listOf(CatalogedMethodOutput("ok", "uint256"))),
        )
        every { blockchain.call(targetAddress, "methodOk", emptyList(), listOf("uint256"), emptyList(), CallBlock.Number(42)) } returns listOf("1")

        val result = resolver.resolve(delivery("""{"broken":{{enriched.broken}},"ok":{{enriched.ok}}}"""), listOf(profileId))

        assertThat(result).isEqualTo("""{"broken":null,"ok":"1"}""")
    }

    @Test
    fun `a call targeting an unregistered contract fails safely without throwing`() {
        val profileId = UUID.randomUUID()
        val call = EnrichmentCall(targetAddress, "balanceOf", emptyList(), listOf(EnrichmentOutputMapping(0, "saldo")))
        val profile = EnrichmentProfile(profileId, "Perfil", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, listOf(call), Instant.now())
        every { enrichmentProfiles.findAllByIds(listOf(profileId)) } returns listOf(profile)
        every { monitoredContracts.findByAddress(targetAddress) } returns null

        val result = resolver.resolve(delivery("""{"saldo":{{enriched.saldo}}}"""), listOf(profileId))

        assertThat(result).isEqualTo("""{"saldo":null}""")
    }

    @Test
    fun `a delivery without blockNumber fails the call safely (legacy deliveries pre-E19)`() {
        val profileId = UUID.randomUUID()
        val call = EnrichmentCall(targetAddress, "balanceOf", emptyList(), listOf(EnrichmentOutputMapping(0, "saldo")))
        val profile = EnrichmentProfile(profileId, "Perfil", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, listOf(call), Instant.now())
        every { enrichmentProfiles.findAllByIds(listOf(profileId)) } returns listOf(profile)

        val result = resolver.resolve(delivery("""{"saldo":{{enriched.saldo}}}""", blockNumber = null), listOf(profileId))

        assertThat(result).isEqualTo("""{"saldo":null}""")
    }
}
