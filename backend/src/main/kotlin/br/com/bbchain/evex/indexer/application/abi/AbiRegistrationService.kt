package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiCache
import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiStorageResolver
import br.com.bbchain.evex.indexer.adapters.outbound.abi.BuiltinInterfaceTemplates
import br.com.bbchain.evex.indexer.adapters.outbound.abi.CachedAbiVersion
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionConflictException
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionOrderException
import br.com.bbchain.evex.indexer.domain.abi.BlockscoutAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.DetectionLevel
import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import br.com.bbchain.evex.indexer.domain.abi.NoFullInterfaceMatchException
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Caso de uso: cadastro e consulta de versões de ABI de um contrato monitorado
 * (`abi-strategy-design.md` §1/§5). Não conhece HTTP, Web3j nem persistência concreta.
 */
@Service
class AbiRegistrationService(
    private val contracts: MonitoredContractPort,
    private val contractAbis: ContractAbiPort,
    private val abiParser: AbiParser,
    private val storageResolver: AbiStorageResolver,
    private val abiCache: AbiCache,
    private val redecodeOrchestrator: RedecodeOrchestrator,
    private val blockscout: BlockscoutPort,
    private val interfaceDetectionService: InterfaceDetectionService,
    private val builtinInterfaceTemplates: BuiltinInterfaceTemplates,
) {
    /**
     * 1) contrato precisa existir (404 se não); 2) resolve o conteúdo bruto da ABI conforme [source]
     * (E6: `UPLOAD_FILE`/`UPLOAD_TEXT` exigem o campo `abi`; `BLOCKSCOUT` busca no Blockscout
     * configurado; `AUTO_DETECTED` usa o `builtinAbi` do primeiro template com match `FULL` — decisão
     * tomada com o usuário: nível `PARTIAL` nunca é aplicado automaticamente); 3) ABI precisa ser JSON
     * de evento válido (400 rápido, antes de qualquer I/O de storage); 4) fecha a versão aberta
     * anterior, se houver (exige `validFromBlock` estritamente crescente); 5) grava o conteúdo na
     * strategy escolhida e persiste os metadados; 6) atualiza o cache em memória com os
     * `EventDescriptor` já parseados no passo 3 — sem reler banco/disco; 7) (E5) dispara a
     * reindexação assíncrona do histórico raw deste contrato — gatilho automático "cadastrar ABI para
     * contrato com histórico decodifica o histórico", incondicional (não pré-checa se há linhas
     * pendentes: se não houver, o primeiro lote do `RedecodeRunner` já retorna `processedCount=0`,
     * custo desprezível) e escopado só por `contractAddress` (não `topic0`/janela de blocos —
     * `AbiDecodingPort.decode()` já resolve internamente qual versão de ABI vale por bloco, DEC-007,
     * então filtrar só por endereço reprocessa corretamente qualquer lacuna histórica). Não bloqueia
     * esta chamada — `tryStart` só dispara o `@Async` e retorna na hora.
     */
    fun register(
        address: String,
        abi: String?,
        source: AbiSource,
        storage: AbiStorageType?,
        validFromBlock: Long,
    ): ContractAbi {
        val normalizedAddress = address.lowercase()
        val contract = contracts.findByAddress(normalizedAddress) ?: throw ContractNotFoundException(normalizedAddress)
        val resolvedAbi = resolveAbiContent(normalizedAddress, abi, source)
        val descriptors = abiParser.parse(resolvedAbi)

        val cachedVersions = abiCache.versionsFor(normalizedAddress).toMutableList()
        val openVersion = contractAbis.findCurrentOpenVersion(normalizedAddress)
        if (openVersion != null) {
            if (validFromBlock <= openVersion.validFromBlock) {
                throw AbiVersionOrderException(normalizedAddress, validFromBlock, openVersion.validFromBlock)
            }
            val closedToBlock = validFromBlock - 1
            contractAbis.save(openVersion.copy(validToBlock = closedToBlock))
            val cachedIndex = cachedVersions.indexOfFirst { it.abiId == openVersion.id }
            if (cachedIndex >= 0) {
                cachedVersions[cachedIndex] = cachedVersions[cachedIndex].copy(validToBlock = closedToBlock)
            }
        }

        val storageType = storage ?: storageResolver.defaultType
        val location = storageResolver.resolve(storageType).store(resolvedAbi)

        val newAbi = ContractAbi(
            id = UUID.randomUUID(),
            contractAddress = normalizedAddress,
            network = contract.network,
            source = source,
            storageType = storageType,
            storageLocation = location,
            validFromBlock = validFromBlock,
            validToBlock = null,
            createdAt = Instant.now(),
        )
        // DEC-022: a checagem de "existe versão aberta?" acima (findCurrentOpenVersion) não é atômica
        // com este insert — 2 requisições concorrentes pro mesmo endereço podem ambas ler "sem versão
        // aberta" e ambas tentar inserir aqui. A unique constraint parcial de banco
        // (contract_abis_unique_open_version, V1__contract_abis_unique_open_version.sql) pega essa
        // corrida; a 2ª chamada a bater aqui recebe DataIntegrityViolationException, traduzida pra
        // AbiVersionConflictException (409) em vez de vazar o erro cru do banco.
        val saved = try {
            contractAbis.save(newAbi)
        } catch (ex: DataIntegrityViolationException) {
            throw AbiVersionConflictException(normalizedAddress)
        }

        cachedVersions.add(
            CachedAbiVersion(
                abiId = saved.id,
                validFromBlock = saved.validFromBlock,
                validToBlock = saved.validToBlock,
                eventDescriptors = descriptors,
            ),
        )
        abiCache.put(normalizedAddress, cachedVersions)

        redecodeOrchestrator.tryStart(RedecodeFilter(contractAddress = normalizedAddress))

        return saved
    }

    fun listVersions(address: String): List<ContractAbi> {
        val normalizedAddress = address.lowercase()
        if (contracts.findByAddress(normalizedAddress) == null) {
            throw ContractNotFoundException(normalizedAddress)
        }
        return contractAbis.findAllByContractAddress(normalizedAddress)
    }

    /**
     * Resolve o conteúdo bruto (JSON) da ABI conforme a origem `source` — os 4 pontos de variação da
     * "Strategy de origem do cadastro" descrita em `abi-strategy-design.md` §4 (`AbiIngestionStrategy`),
     * implementados aqui como um `when` (não uma hierarquia de interfaces separada — só 4 ramos, sem
     * estado próprio, não justificava o overhead de mais 4 classes/beans). Todas produzem o mesmo tipo
     * de saída (JSON de ABI válido); o resto do fluxo de cadastro não depende de qual origem gerou o
     * conteúdo.
     */
    private fun resolveAbiContent(address: String, abi: String?, source: AbiSource): String = when (source) {
        AbiSource.UPLOAD_FILE, AbiSource.UPLOAD_TEXT ->
            abi?.takeIf { it.isNotBlank() }
                ?: throw InvalidAbiContentException("Campo `abi` é obrigatório para source=$source.")

        AbiSource.BLOCKSCOUT ->
            blockscout.getAbi(address) ?: throw BlockscoutAbiNotFoundException(address)

        AbiSource.AUTO_DETECTED -> {
            val fullMatch = interfaceDetectionService.suggest(address)
                .firstOrNull()
                ?.takeIf { it.level == DetectionLevel.FULL }
                ?: throw NoFullInterfaceMatchException(address)
            // findByName nunca deveria retornar null aqui — o nome vem do próprio InterfaceDetector,
            // que só conhece os templates de BuiltinInterfaceTemplates — mas não custa ser defensivo
            // em vez de propagar um NPE se algum dia isso divergir.
            builtinInterfaceTemplates.findByName(fullMatch.templateName)?.builtinAbi
                ?: throw NoFullInterfaceMatchException(address)
        }
    }
}
