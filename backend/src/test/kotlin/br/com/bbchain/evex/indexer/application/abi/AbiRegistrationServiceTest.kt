package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiCache
import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiStorageResolver
import br.com.bbchain.evex.indexer.adapters.outbound.abi.BuiltinInterfaceTemplates
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStoragePort
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionConflictException
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionOrderException
import br.com.bbchain.evex.indexer.domain.abi.BlockscoutAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.DetectionLevel
import br.com.bbchain.evex.indexer.domain.abi.InterfaceMatch
import br.com.bbchain.evex.indexer.domain.abi.NoFullInterfaceMatchException
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit test — [MonitoredContractPort]/[ContractAbiPort]/etc. mockados via MockK (DEC-009). */
class AbiRegistrationServiceTest {

    private val contracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val storageResolver: AbiStorageResolver = mockk()
    private val abiCache: AbiCache = mockk()
    private val redecodeOrchestrator: RedecodeOrchestrator = mockk()
    private val blockscout: BlockscoutPort = mockk()
    private val interfaceDetectionService: InterfaceDetectionService = mockk()
    private val builtinInterfaceTemplates: BuiltinInterfaceTemplates = mockk()
    private val abiParser = AbiParser() // real: só parsing puro, não vale a pena mockar

    private lateinit var service: AbiRegistrationService

    private val transferAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private val monitoredContract = MonitoredContract(
        id = UUID.randomUUID(),
        address = "0xaaaa000000000000000000000000000000aaaa",
        name = "Token",
        description = null,
        version = null,
        network = Network.TESTNET,
        status = ContractStatus.ACTIVE,
        lineageId = null,
        createdAt = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        service = AbiRegistrationService(
            contracts,
            contractAbis,
            abiParser,
            storageResolver,
            abiCache,
            redecodeOrchestrator,
            blockscout,
            interfaceDetectionService,
            builtinInterfaceTemplates,
        )
        every { abiCache.versionsFor(any()) } returns emptyList()
        every { abiCache.put(any(), any()) } returns Unit
        every { redecodeOrchestrator.tryStart(any()) } returns true
    }

    @Test
    fun `register throws ContractNotFoundException when the contract does not exist`() {
        every { contracts.findByAddress(any()) } returns null

        assertThatThrownBy {
            service.register(monitoredContract.address, transferAbi, AbiSource.UPLOAD_TEXT, null, 0L)
        }.isInstanceOf(ContractNotFoundException::class.java)

        verify(exactly = 0) { contractAbis.save(any()) }
        verify(exactly = 0) { redecodeOrchestrator.tryStart(any()) }
    }

    @Test
    fun `register throws InvalidAbiContentException before touching storage when the ABI JSON is malformed`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract

        assertThatThrownBy {
            service.register(monitoredContract.address, "not json", AbiSource.UPLOAD_TEXT, null, 0L)
        }.isInstanceOf(br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException::class.java)

        verify(exactly = 0) { contractAbis.save(any()) }
        verify(exactly = 0) { storageResolver.resolve(any()) }
        verify(exactly = 0) { redecodeOrchestrator.tryStart(any()) }
    }

    @Test
    fun `register with no previous version stores the content, persists metadata and warms the cache`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        every { contractAbis.findCurrentOpenVersion(monitoredContract.address) } returns null
        val storagePort: AbiStoragePort = mockk()
        every { storageResolver.defaultType } returns AbiStorageType.DATABASE
        every { storageResolver.resolve(AbiStorageType.DATABASE) } returns storagePort
        every { storagePort.store(transferAbi) } returns StorageLocation(abiContent = transferAbi, storagePath = null)
        val savedSlot = slot<ContractAbi>()
        every { contractAbis.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.register(monitoredContract.address, transferAbi, AbiSource.UPLOAD_TEXT, null, 0L)

        assertThat(result.contractAddress).isEqualTo(monitoredContract.address)
        assertThat(result.network).isEqualTo(Network.TESTNET)
        assertThat(result.source).isEqualTo(AbiSource.UPLOAD_TEXT)
        assertThat(result.storageType).isEqualTo(AbiStorageType.DATABASE)
        assertThat(result.validFromBlock).isEqualTo(0L)
        assertThat(result.validToBlock).isNull()
        verify { abiCache.put(monitoredContract.address, any()) }
        verify { redecodeOrchestrator.tryStart(RedecodeFilter(contractAddress = monitoredContract.address)) }
    }

    @Test
    fun `register translates a DataIntegrityViolationException from the concurrent-insert race (DEC-022) into AbiVersionConflictException`() {
        // Simula a corrida da DEC-022: 2 requisições concorrentes leem "sem versão aberta" e ambas
        // tentam inserir uma nova versão aberta — a unique constraint parcial de banco
        // (contract_abis_unique_open_version, V1__contract_abis_unique_open_version.sql) barra a 2ª,
        // que chega ao serviço como DataIntegrityViolationException.
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        every { contractAbis.findCurrentOpenVersion(monitoredContract.address) } returns null
        val storagePort: AbiStoragePort = mockk()
        every { storageResolver.defaultType } returns AbiStorageType.DATABASE
        every { storageResolver.resolve(AbiStorageType.DATABASE) } returns storagePort
        every { storagePort.store(transferAbi) } returns StorageLocation(abiContent = transferAbi, storagePath = null)
        every { contractAbis.save(any()) } throws org.springframework.dao.DataIntegrityViolationException("duplicate key")

        assertThatThrownBy {
            service.register(monitoredContract.address, transferAbi, AbiSource.UPLOAD_TEXT, null, 0L)
        }.isInstanceOf(AbiVersionConflictException::class.java)
    }

    @Test
    fun `register closes the previously open version, setting its validToBlock to the new version's block minus one`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        val openVersion = ContractAbi(
            id = UUID.randomUUID(),
            contractAddress = monitoredContract.address,
            network = Network.TESTNET,
            source = AbiSource.UPLOAD_TEXT,
            storageType = AbiStorageType.DATABASE,
            storageLocation = StorageLocation(abiContent = transferAbi, storagePath = null),
            validFromBlock = 0,
            validToBlock = null,
            createdAt = Instant.now(),
        )
        every { contractAbis.findCurrentOpenVersion(monitoredContract.address) } returns openVersion
        val closedSlot = slot<ContractAbi>()
        every { contractAbis.save(capture(closedSlot)) } answers { closedSlot.captured }
        val storagePort: AbiStoragePort = mockk()
        every { storageResolver.defaultType } returns AbiStorageType.DATABASE
        every { storageResolver.resolve(AbiStorageType.DATABASE) } returns storagePort
        every { storagePort.store(any()) } returns StorageLocation(abiContent = transferAbi, storagePath = null)

        service.register(monitoredContract.address, transferAbi, AbiSource.UPLOAD_TEXT, null, validFromBlock = 500L)

        verify {
            contractAbis.save(match { it.id == openVersion.id && it.validToBlock == 499L })
        }
    }

    @Test
    fun `register rejects a validFromBlock that does not strictly advance past the currently open version`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        val openVersion = ContractAbi(
            id = UUID.randomUUID(),
            contractAddress = monitoredContract.address,
            network = Network.TESTNET,
            source = AbiSource.UPLOAD_TEXT,
            storageType = AbiStorageType.DATABASE,
            storageLocation = StorageLocation(abiContent = transferAbi, storagePath = null),
            validFromBlock = 500,
            validToBlock = null,
            createdAt = Instant.now(),
        )
        every { contractAbis.findCurrentOpenVersion(monitoredContract.address) } returns openVersion

        assertThatThrownBy {
            service.register(monitoredContract.address, transferAbi, AbiSource.UPLOAD_TEXT, null, validFromBlock = 500L)
        }.isInstanceOf(AbiVersionOrderException::class.java)

        verify(exactly = 0) { contractAbis.save(any()) }
        verify(exactly = 0) { storageResolver.resolve(any()) }
    }

    @Test
    fun `register with UPLOAD_TEXT and a blank abi throws InvalidAbiContentException before touching storage`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract

        assertThatThrownBy {
            service.register(monitoredContract.address, "   ", AbiSource.UPLOAD_TEXT, null, 0L)
        }.isInstanceOf(br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException::class.java)

        verify(exactly = 0) { storageResolver.resolve(any()) }
        verify(exactly = 0) { contractAbis.save(any()) }
    }

    @Test
    fun `register with UPLOAD_TEXT and a null abi throws InvalidAbiContentException`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract

        assertThatThrownBy {
            service.register(monitoredContract.address, null, AbiSource.UPLOAD_TEXT, null, 0L)
        }.isInstanceOf(br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException::class.java)

        verify(exactly = 0) { blockscout.getAbi(any()) }
    }

    @Test
    fun `register with BLOCKSCOUT fetches the ABI from BlockscoutPort and ignores the abi field of the request`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        every { contractAbis.findCurrentOpenVersion(monitoredContract.address) } returns null
        every { blockscout.getAbi(monitoredContract.address) } returns transferAbi
        val storagePort: AbiStoragePort = mockk()
        every { storageResolver.defaultType } returns AbiStorageType.DATABASE
        every { storageResolver.resolve(AbiStorageType.DATABASE) } returns storagePort
        every { storagePort.store(transferAbi) } returns StorageLocation(abiContent = transferAbi, storagePath = null)
        val savedSlot = slot<ContractAbi>()
        every { contractAbis.save(capture(savedSlot)) } answers { savedSlot.captured }

        // O `abi` do request é ignorado de propósito (source=BLOCKSCOUT) — passado como um payload
        // claramente diferente do que o BlockscoutPort devolve, pra provar que não é usado.
        val result = service.register(monitoredContract.address, "ignored-payload", AbiSource.BLOCKSCOUT, null, 0L)

        assertThat(result.source).isEqualTo(AbiSource.BLOCKSCOUT)
        verify { storagePort.store(transferAbi) }
    }

    @Test
    fun `register with BLOCKSCOUT throws BlockscoutAbiNotFoundException when the contract is not verified`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        every { blockscout.getAbi(monitoredContract.address) } returns null

        assertThatThrownBy {
            service.register(monitoredContract.address, null, AbiSource.BLOCKSCOUT, null, 0L)
        }.isInstanceOf(BlockscoutAbiNotFoundException::class.java)

        verify(exactly = 0) { contractAbis.save(any()) }
        verify(exactly = 0) { storageResolver.resolve(any()) }
    }

    @Test
    fun `register with AUTO_DETECTED uses the builtinAbi of the first FULL match`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        every { contractAbis.findCurrentOpenVersion(monitoredContract.address) } returns null
        every { interfaceDetectionService.suggest(monitoredContract.address) } returns listOf(
            InterfaceMatch(
                templateName = "ERC20",
                level = DetectionLevel.FULL,
                matchedTopic0s = setOf("0xdead"),
                missingTopic0s = emptySet(),
            ),
        )
        every { builtinInterfaceTemplates.findByName("ERC20") } returns br.com.bbchain.evex.indexer.domain.abi.InterfaceTemplate(
            name = "ERC20",
            eventTopic0s = setOf("0xdead"),
            builtinAbi = transferAbi,
        )
        val storagePort: AbiStoragePort = mockk()
        every { storageResolver.defaultType } returns AbiStorageType.DATABASE
        every { storageResolver.resolve(AbiStorageType.DATABASE) } returns storagePort
        every { storagePort.store(transferAbi) } returns StorageLocation(abiContent = transferAbi, storagePath = null)
        val savedSlot = slot<ContractAbi>()
        every { contractAbis.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.register(monitoredContract.address, null, AbiSource.AUTO_DETECTED, null, 0L)

        assertThat(result.source).isEqualTo(AbiSource.AUTO_DETECTED)
        verify { storagePort.store(transferAbi) }
    }

    @Test
    fun `register with AUTO_DETECTED and only a PARTIAL match throws NoFullInterfaceMatchException, never registers`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        every { interfaceDetectionService.suggest(monitoredContract.address) } returns listOf(
            InterfaceMatch(
                templateName = "ERC20",
                level = DetectionLevel.PARTIAL,
                matchedTopic0s = setOf("0xdead"),
                missingTopic0s = setOf("0xbeef"),
            ),
        )

        assertThatThrownBy {
            service.register(monitoredContract.address, null, AbiSource.AUTO_DETECTED, null, 0L)
        }.isInstanceOf(NoFullInterfaceMatchException::class.java)

        verify(exactly = 0) { contractAbis.save(any()) }
        verify(exactly = 0) { storageResolver.resolve(any()) }
    }

    @Test
    fun `register with AUTO_DETECTED and no match at all throws NoFullInterfaceMatchException`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        every { interfaceDetectionService.suggest(monitoredContract.address) } returns emptyList()

        assertThatThrownBy {
            service.register(monitoredContract.address, null, AbiSource.AUTO_DETECTED, null, 0L)
        }.isInstanceOf(NoFullInterfaceMatchException::class.java)
    }

    @Test
    fun `listVersions throws ContractNotFoundException when the contract does not exist`() {
        every { contracts.findByAddress(any()) } returns null

        assertThatThrownBy { service.listVersions(monitoredContract.address) }
            .isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `listVersions delegates to the port when the contract exists`() {
        every { contracts.findByAddress(monitoredContract.address) } returns monitoredContract
        val versions = listOf(
            ContractAbi(
                id = UUID.randomUUID(),
                contractAddress = monitoredContract.address,
                network = Network.TESTNET,
                source = AbiSource.UPLOAD_TEXT,
                storageType = AbiStorageType.DATABASE,
                storageLocation = StorageLocation(abiContent = transferAbi, storagePath = null),
                validFromBlock = 0,
                validToBlock = null,
                createdAt = Instant.now(),
            ),
        )
        every { contractAbis.findAllByContractAddress(monitoredContract.address) } returns versions

        assertThat(service.listVersions(monitoredContract.address)).isEqualTo(versions)
    }
}
