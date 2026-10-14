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
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.DetectionLevel
import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import br.com.bbchain.evex.indexer.domain.abi.NoFullInterfaceMatchException
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
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
    private val versionWriter: AbiVersionWriter,
    private val blockscoutAbiResolver: BlockscoutAbiResolver,
) {
    private val log = LoggerFactory.getLogger(AbiRegistrationService::class.java)

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
     *
     * Bloco inicial ([validFromBlock]): se informado, vale exatamente o valor (inclusive 0 — escolha
     * explícita nunca é sobrescrita). Se `null` e [source] = `BLOCKSCOUT`, usa o bloco de criação do
     * contrato cadastrado (para proxy, o do próprio proxy) via [BlockscoutPort.getCreationBlock]; sem dado
     * ou com falha do Blockscout, cai em 0 (WARN só com o tipo da causa, sem derrubar o cadastro). Demais
     * origens usam 0. É uma aproximação de versão única: para faixas de blocos por upgrade de proxy, o
     * caminho correto é o import de histórico (`history-plan`/`history-import`). A regra de ordem contra a
     * versão aberta segue inalterada (valor resolvido <= vigente => [AbiVersionOrderException]).
     */
    fun register(
        address: String,
        abi: String?,
        source: AbiSource,
        storage: AbiStorageType?,
        validFromBlock: Long? = null,
    ): ContractAbi {
        val normalizedAddress = address.lowercase()
        val contract = contracts.findByAddress(normalizedAddress) ?: throw ContractNotFoundException(normalizedAddress)
        val resolvedAbi = resolveAbiContent(normalizedAddress, abi, source)
        val resolvedFrom = validFromBlock ?: defaultValidFromBlock(normalizedAddress, source)
        return registerResolved(
            contract = contract,
            drafts = listOf(AbiVersionDraft(resolvedAbi, resolvedFrom)),
            source = source,
            storage = storage,
            replaceLegacy = false,
        ).versions.single()
    }

    private fun defaultValidFromBlock(address: String, source: AbiSource): Long {
        if (source != AbiSource.BLOCKSCOUT) return 0L
        return try {
            blockscout.getCreationBlock(address) ?: 0L
        } catch (ex: BlockscoutUnavailableException) {
            log.warn("Bloco de criação indisponível no Blockscout ({}); usando validFromBlock=0", ex.javaClass.simpleName)
            0L
        }
    }

    /**
     * Cadastro em lote (DEC-105): N versões em `validFromBlock` estritamente crescente, todas no mesmo
     * commit (atômico), com o cache atualizado UMA vez e UM único redecode ao final. [source] vale para
     * todas as versões do lote. Com [replaceBlockscoutLegacy], apaga antes (mesma transação) as versões
     * legadas `BLOCKSCOUT` sem `implementation_address` (nunca `UPLOAD_*`).
     */
    fun registerAll(
        address: String,
        versions: List<AbiVersionDraft>,
        source: AbiSource,
        storage: AbiStorageType?,
        replaceBlockscoutLegacy: Boolean = false,
    ): AbiBatchRegistrationResult {
        val normalizedAddress = address.lowercase()
        val contract = contracts.findByAddress(normalizedAddress) ?: throw ContractNotFoundException(normalizedAddress)
        return registerResolved(contract, versions, source, storage, replaceBlockscoutLegacy)
    }

    private fun registerResolved(
        contract: MonitoredContract,
        drafts: List<AbiVersionDraft>,
        source: AbiSource,
        storage: AbiStorageType?,
        replaceLegacy: Boolean,
    ): AbiBatchRegistrationResult {
        val normalizedAddress = contract.address.lowercase()
        if (drafts.isEmpty()) throw InvalidAbiContentException("Lote de versões de ABI vazio.")

        // A) parseia todas antes de qualquer I/O de storage: uma ABI inválida derruba o lote inteiro.
        val parsed = drafts.map { abiParser.parse(it.abiJson) }

        // B) ordem estritamente crescente dentro do lote e contra a versão aberta (com replaceLegacy a
        // versão aberta pode ser justamente a legada que será apagada: a checagem final fica no writer,
        // dentro da transação, já sem as legadas).
        drafts.zipWithNext().forEach { (a, b) ->
            if (b.validFromBlock <= a.validFromBlock) {
                throw AbiVersionOrderException(normalizedAddress, b.validFromBlock, a.validFromBlock)
            }
        }
        if (!replaceLegacy) {
            contractAbis.findCurrentOpenVersion(normalizedAddress)?.let { open ->
                if (drafts.first().validFromBlock <= open.validFromBlock) {
                    throw AbiVersionOrderException(normalizedAddress, drafts.first().validFromBlock, open.validFromBlock)
                }
            }
        }

        // C) grava o conteúdo no storage (fora da transação; num rollback pode sobrar conteúdo órfão
        // em storage LOCAL) e persiste o lote atomicamente.
        val storageType = storage ?: storageResolver.defaultType
        val storagePort = storageResolver.resolve(storageType)
        val toSave = drafts.map { draft ->
            ContractAbi(
                id = UUID.randomUUID(),
                contractAddress = normalizedAddress,
                network = contract.network,
                source = source,
                storageType = storageType,
                storageLocation = storagePort.store(draft.abiJson),
                validFromBlock = draft.validFromBlock,
                validToBlock = null,
                createdAt = Instant.now(),
                implementationAddress = draft.implementationAddress,
            )
        }
        // DEC-022: a checagem de "existe versão aberta?" não é atômica com o insert — 2 requisições
        // concorrentes pro mesmo endereço podem ambas ler "sem versão aberta". A unique constraint
        // parcial (contract_abis_unique_open_version, V1) pega a corrida; a violação pode estourar só no
        // commit (saída do bean transacional), por isso o catch fica aqui, no chamador.
        val result = try {
            versionWriter.appendAll(normalizedAddress, toSave, replaceLegacy)
        } catch (ex: DataIntegrityViolationException) {
            throw AbiVersionConflictException(normalizedAddress)
        }

        // D) só após o commit: cache atualizado UMA vez com a lista final.
        val cachedVersions = abiCache.versionsFor(normalizedAddress)
            .filter { it.abiId !in result.deletedIds }
            .map { cached ->
                if (cached.abiId == result.closedPrevious?.id) cached.copy(validToBlock = result.closedPrevious.validToBlock) else cached
            }
            .toMutableList()
        result.saved.forEachIndexed { index, saved ->
            cachedVersions.add(
                CachedAbiVersion(
                    abiId = saved.id,
                    validFromBlock = saved.validFromBlock,
                    validToBlock = saved.validToBlock,
                    eventDescriptors = parsed[index],
                ),
            )
        }
        abiCache.put(normalizedAddress, cachedVersions)

        // E) UM redecode, escopado por endereço (AbiDecodingPort resolve a versão por bloco, DEC-007).
        val redecodeStarted = redecodeOrchestrator.tryStart(RedecodeFilter(contractAddress = normalizedAddress))
        return AbiBatchRegistrationResult(versions = result.saved, redecodeStarted = redecodeStarted)
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

        AbiSource.BLOCKSCOUT -> blockscoutAbiResolver.resolveForRegistration(address)

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
