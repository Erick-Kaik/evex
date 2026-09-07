package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.ContractsApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AbiEntry as AbiEntryDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AbiEntryKind as AbiEntryKindDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AbiEntryParam as AbiEntryParamDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AbiSource as AbiSourceDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.AbiStorageType as AbiStorageTypeDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Contract as ContractDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractAbi as ContractAbiDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractEvent as ContractEventDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractEventInput as ContractEventInputDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractMethod as ContractMethodDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractMethodInput as ContractMethodInputDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractMethodOutput as ContractMethodOutputDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ContractStatus as ContractStatusDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.CreateContractPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.DetectionLevel as DetectionLevelDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.InterfaceSuggestion
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.RegisterAbiPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateContractPayload
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.UpdateContractStatusPayload
import br.com.bbchain.evex.indexer.application.abi.AbiRegistrationService
import br.com.bbchain.evex.indexer.application.abi.ContractAbiEntryCatalogService
import br.com.bbchain.evex.indexer.application.abi.ContractEventCatalogService
import br.com.bbchain.evex.indexer.application.abi.ContractMethodCatalogService
import br.com.bbchain.evex.indexer.application.abi.InterfaceDetectionService
import br.com.bbchain.evex.indexer.application.contract.MonitoredContractService
import br.com.bbchain.evex.indexer.domain.abi.AbiEntryKind
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.CatalogedAbiEntry
import br.com.bbchain.evex.indexer.domain.abi.CatalogedAbiEntryParam
import br.com.bbchain.evex.indexer.domain.abi.CatalogedEvent
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethod
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.DetectionLevel
import br.com.bbchain.evex.indexer.domain.abi.InterfaceMatch
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import org.springframework.stereotype.Service

/**
 * Implementação fina de [ContractsApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [MonitoredContractService]/[AbiRegistrationService]/[InterfaceDetectionService]. Nenhuma regra de
 * negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class ContractsApiServiceImpl(
    private val monitoredContractService: MonitoredContractService,
    private val abiRegistrationService: AbiRegistrationService,
    private val interfaceDetectionService: InterfaceDetectionService,
    private val contractEventCatalogService: ContractEventCatalogService,
    private val contractMethodCatalogService: ContractMethodCatalogService,
    private val contractAbiEntryCatalogService: ContractAbiEntryCatalogService,
) : ContractsApiService {

    override fun createMonitoredContract(createContractPayload: CreateContractPayload): ContractDto {
        val contract = monitoredContractService.register(
            address = createContractPayload.address,
            name = createContractPayload.name,
            network = createContractPayload.network.toDomain(),
            description = createContractPayload.description,
            version = createContractPayload.version,
        )
        return contract.toDto()
    }

    override fun getMonitoredContract(address: kotlin.String): ContractDto =
        monitoredContractService.getByAddress(address).toDto()

    override fun listMonitoredContracts(network: NetworkDto?, page: kotlin.Int, size: kotlin.Int): ContractPage {
        val result = monitoredContractService.list(network?.toDomain(), page, size)
        return ContractPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    override fun updateMonitoredContract(address: kotlin.String, updateContractPayload: UpdateContractPayload): ContractDto {
        val contract = monitoredContractService.update(
            address = address,
            name = updateContractPayload.name,
            description = updateContractPayload.description,
            version = updateContractPayload.version,
        )
        return contract.toDto()
    }

    override fun updateContractStatus(address: kotlin.String, updateContractStatusPayload: UpdateContractStatusPayload): ContractDto {
        val contract = monitoredContractService.updateStatus(
            address = address,
            status = updateContractStatusPayload.status.toDomain(),
        )
        return contract.toDto()
    }

    override fun registerContractAbi(address: kotlin.String, registerAbiPayload: RegisterAbiPayload): ContractAbiDto {
        val contractAbi = abiRegistrationService.register(
            address = address,
            abi = registerAbiPayload.abi,
            source = registerAbiPayload.source.toDomain(),
            storage = registerAbiPayload.storage?.toDomain(),
            validFromBlock = registerAbiPayload.validFromBlock ?: 0L,
        )
        return contractAbi.toDto()
    }

    override fun listContractAbiVersions(address: kotlin.String): MutableList<ContractAbiDto> =
        abiRegistrationService.listVersions(address).map { it.toDto() }.toMutableList()

    override fun listContractEvents(address: kotlin.String): MutableList<ContractEventDto> =
        contractEventCatalogService.catalogFor(address).map { it.toDto() }.toMutableList()

    override fun listContractMethods(address: kotlin.String): MutableList<ContractMethodDto> =
        contractMethodCatalogService.catalogFor(address).map { it.toDto() }.toMutableList()

    override fun listContractAbiEntries(address: kotlin.String): MutableList<AbiEntryDto> =
        contractAbiEntryCatalogService.catalogFor(address).map { it.toDto() }.toMutableList()

    /**
     * 404 se o contrato não estiver cadastrado — `getByAddress` (não usado no resto deste método)
     * lança [br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException] sozinho;
     * `InterfaceDetectionService.suggest` não sabe (nem precisa saber) se o endereço é um
     * `MonitoredContract` — ele funciona pra qualquer endereço com `topic0`s observados, DEC-001.
     */
    override fun suggestInterface(address: kotlin.String): MutableList<InterfaceSuggestion> {
        monitoredContractService.getByAddress(address)
        return interfaceDetectionService.suggest(address).map { it.toDto() }.toMutableList()
    }

    private fun InterfaceMatch.toDto(): InterfaceSuggestion = InterfaceSuggestion(
        templateName = templateName,
        level = level.toDto(),
        matchedTopic0s = matchedTopic0s.toMutableList(),
        missingTopic0s = missingTopic0s.toMutableList(),
    )

    private fun DetectionLevel.toDto(): DetectionLevelDto = DetectionLevelDto.valueOf(this.name)

    private fun MonitoredContract.toDto(): ContractDto = ContractDto(
        id = id,
        address = address,
        name = name,
        description = description,
        version = version,
        network = network.toDto(),
        status = status.toDto(),
    )

    private fun NetworkDto.toDomain(): Network = Network.valueOf(this.name)

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    private fun ContractStatusDto.toDomain(): ContractStatus = ContractStatus.valueOf(this.name)

    private fun ContractStatus.toDto(): ContractStatusDto = ContractStatusDto.valueOf(this.name)

    private fun AbiSourceDto.toDomain(): AbiSource = AbiSource.valueOf(this.name)

    private fun AbiSource.toDto(): AbiSourceDto = AbiSourceDto.valueOf(this.name)

    private fun AbiStorageTypeDto.toDomain(): AbiStorageType = AbiStorageType.valueOf(this.name)

    private fun AbiStorageType.toDto(): AbiStorageTypeDto = AbiStorageTypeDto.valueOf(this.name)

    private fun CatalogedEvent.toDto(): ContractEventDto = ContractEventDto(
        name = name,
        inputs = inputs.map { ContractEventInputDto(name = it.name, type = it.type, indexed = it.indexed) }.toMutableList(),
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
    )

    private fun CatalogedMethod.toDto(): ContractMethodDto = ContractMethodDto(
        name = name,
        inputs = inputs.map { ContractMethodInputDto(name = it.name, type = it.type) }.toMutableList(),
        outputs = outputs.map { ContractMethodOutputDto(name = it.name, type = it.type) }.toMutableList(),
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
    )

    private fun AbiEntryKind.toDto(): AbiEntryKindDto = AbiEntryKindDto.valueOf(this.name)

    private fun CatalogedAbiEntryParam.toDto(): AbiEntryParamDto = AbiEntryParamDto(
        name = name,
        type = type,
        indexed = indexed,
    )

    private fun CatalogedAbiEntry.toDto(): AbiEntryDto = AbiEntryDto(
        kind = kind.toDto(),
        name = name,
        stateMutability = stateMutability,
        inputs = inputs.map { it.toDto() }.toMutableList(),
        outputs = outputs.map { it.toDto() }.toMutableList(),
        anonymous = anonymous,
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
    )

    private fun ContractAbi.toDto(): ContractAbiDto = ContractAbiDto(
        id = id,
        contractAddress = contractAddress,
        network = network.toDto(),
        source = source.toDto(),
        storageType = storageType.toDto(),
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
        createdAt = createdAt.atOffset(java.time.ZoneOffset.UTC),
    )
}
