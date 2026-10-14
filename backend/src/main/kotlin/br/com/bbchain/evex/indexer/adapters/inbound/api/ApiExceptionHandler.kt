package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.ApiError
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryPlanMismatchException
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionConflictException
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionOrderException
import br.com.bbchain.evex.indexer.domain.abi.BlockscoutAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.abi.BlockscoutImplementationAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import br.com.bbchain.evex.indexer.domain.abi.NoFullInterfaceMatchException
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.block.BlockResolutionFailedException
import br.com.bbchain.evex.indexer.domain.block.InvalidBlockDirectionException
import br.com.bbchain.evex.indexer.domain.balance.InvalidBalanceMonitorConfigException
import br.com.bbchain.evex.indexer.domain.common.NotImplementedYetException
import br.com.bbchain.evex.indexer.domain.contract.ContractAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.LineageNetworkMismatchException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileNotFoundException
import br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentBlockSpecException
import br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentProfileException
import br.com.bbchain.evex.indexer.domain.event.InvalidEventCursorException
import br.com.bbchain.evex.indexer.domain.model.EntityModelNotFoundException
import br.com.bbchain.evex.indexer.domain.model.InvalidEntityModelException
import br.com.bbchain.evex.indexer.domain.wallet.WalletAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.wallet.WalletNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.InvalidWebhookConfigException
import br.com.bbchain.evex.indexer.domain.webhook.InvalidWebhookRequestException
import br.com.bbchain.evex.indexer.domain.webhook.RateLimitExceededException
import br.com.bbchain.evex.indexer.domain.webhook.TooManyEventsException
import br.com.bbchain.evex.indexer.domain.webhook.UrlNotAllowedException
import br.com.bbchain.evex.indexer.domain.webhook.UrlOverrideDisabledException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookInactiveException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import jakarta.validation.ConstraintViolationException
import java.time.OffsetDateTime
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Traduz exceções de domínio/validação para o formato [ApiError] documentado no spec (DEC-013).
 * Cresce conforme novos recursos declaram seus próprios status de erro — não é um catch-all genérico.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ContractNotFoundException::class)
    fun handleContractNotFound(ex: ContractNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(code = "CONTRACT_NOT_FOUND", message = ex.message ?: "Contrato não encontrado", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/contracts` com `address` já cadastrado — `address` é chave natural, unique no banco. */
    @ExceptionHandler(ContractAlreadyExistsException::class)
    fun handleContractAlreadyExists(ex: ContractAlreadyExistsException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(code = "CONTRACT_ALREADY_EXISTS", message = ex.message ?: "Contrato já cadastrado", timestamp = OffsetDateTime.now()),
        )

    /**
     * Fallback para a corrida entre a checagem de duplicidade em memória (`MonitoredContractService.
     * register()`, que lança [ContractAlreadyExistsException] no caminho comum) e a unique constraint
     * do banco: duas requisições concorrentes com o mesmo `address` podem ambas passar no `findByAddress`
     * antes de qualquer uma salvar. Sem este handler, a segunda colidiria na constraint e vazaria o
     * formato de erro default do Spring em vez do [ApiError] do contrato.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(code = "CONTRACT_ALREADY_EXISTS", message = "Contrato já cadastrado", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/contracts` com `predecessorAddress` de uma `network` diferente do contrato novo (E29/DEC-096/DEC-097). */
    @ExceptionHandler(LineageNetworkMismatchException::class)
    fun handleLineageNetworkMismatch(ex: LineageNetworkMismatchException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "LINEAGE_NETWORK_MISMATCH", message = ex.message ?: "Predecessor de rede diferente", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/contracts/{address}/abi` com ABI JSON malformada ou schema/tipo Solidity não suportado. */
    @ExceptionHandler(InvalidAbiContentException::class)
    fun handleInvalidAbiContent(ex: InvalidAbiContentException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_ABI_CONTENT", message = ex.message ?: "ABI inválida", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/contracts/{address}/abi` com `validFromBlock` retrocedendo em relação à versão aberta atual. */
    @ExceptionHandler(AbiVersionOrderException::class)
    fun handleAbiVersionOrder(ex: AbiVersionOrderException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "ABI_VERSION_ORDER_INVALID", message = ex.message ?: "Ordem de versão de ABI inválida", timestamp = OffsetDateTime.now()),
        )

    /**
     * `POST /v1/contracts/{address}/abi` concorrente pro mesmo endereço colidiu na unique constraint
     * parcial de banco (DEC-022) — `AbiRegistrationService` já traduz a `DataIntegrityViolationException`
     * crua pra esta exceção de domínio antes dela chegar aqui (não reaproveita o handler genérico de
     * `DataIntegrityViolationException` abaixo, que já é usado pela constraint de `monitored_contracts.
     * address`; Spring não permite 2 handlers pro mesmo tipo de exceção neste `@RestControllerAdvice`).
     */
    @ExceptionHandler(AbiVersionConflictException::class)
    fun handleAbiVersionConflict(ex: AbiVersionConflictException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(code = "ABI_VERSION_CONFLICT", message = ex.message ?: "Conflito de versão de ABI", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/contracts/{address}/abi/history-import` com `expectedFingerprint` diferente do plano recalculado (DEC-105). */
    @ExceptionHandler(AbiHistoryPlanMismatchException::class)
    fun handleAbiHistoryPlanMismatch(ex: AbiHistoryPlanMismatchException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(code = "ABI_HISTORY_PLAN_CHANGED", message = ex.message ?: "O plano de importação mudou", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/contracts/{address}/abi` com `source=BLOCKSCOUT` sem ABI verificada pro endereço no Blockscout. */
    @ExceptionHandler(BlockscoutAbiNotFoundException::class)
    fun handleBlockscoutAbiNotFound(ex: BlockscoutAbiNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(code = "BLOCKSCOUT_ABI_NOT_FOUND", message = ex.message ?: "ABI não encontrada no Blockscout", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/contracts/{address}/abi` com `source=BLOCKSCOUT` de proxy cuja implementação não tem ABI verificada (DEC-105). */
    @ExceptionHandler(BlockscoutImplementationAbiNotFoundException::class)
    fun handleBlockscoutImplementationAbiNotFound(ex: BlockscoutImplementationAbiNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ApiError(
                code = "BLOCKSCOUT_IMPLEMENTATION_ABI_NOT_FOUND",
                message = ex.message ?: "Implementação do proxy sem ABI verificada no Blockscout",
                timestamp = OffsetDateTime.now(),
            ),
        )

    /** `POST /v1/contracts/{address}/abi` com `source=AUTO_DETECTED` sem nenhum match de nível FULL. */
    @ExceptionHandler(NoFullInterfaceMatchException::class)
    fun handleNoFullInterfaceMatch(ex: NoFullInterfaceMatchException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "NO_FULL_INTERFACE_MATCH", message = ex.message ?: "Nenhuma interface padrão bateu em match total", timestamp = OffsetDateTime.now()),
        )

    /**
     * Blockscout não configurado (`indexer.blockscout.base-url` vazio) ou chamada HTTP falhou — usado
     * por `GET /v1/discovery/blockscout/contracts`, `source=BLOCKSCOUT` do cadastro de ABI, e o
     * fallback de `InterfaceDetectionService`. Blockscout nunca é dependência crítica do caminho
     * principal (mesmo espírito do doc §4.8 pra storage externo).
     */
    @ExceptionHandler(BlockscoutUnavailableException::class)
    fun handleBlockscoutUnavailable(ex: BlockscoutUnavailableException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ApiError(code = "BLOCKSCOUT_UNAVAILABLE", message = ex.message ?: "Blockscout indisponível", timestamp = OffsetDateTime.now()),
        )

    /** `POST`/`PUT /v1/balance-monitors` com combinação de campos inválida pro `behavior` informado. */
    @ExceptionHandler(InvalidBalanceMonitorConfigException::class)
    fun handleInvalidBalanceMonitorConfig(ex: InvalidBalanceMonitorConfigException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_BALANCE_MONITOR_CONFIG", message = ex.message ?: "Configuração de monitoramento de saldo inválida", timestamp = OffsetDateTime.now()),
        )

    /** Nenhuma configuração de monitoramento de saldo cadastrada com o id informado. */
    @ExceptionHandler(BalanceMonitorConfigNotFoundException::class)
    fun handleBalanceMonitorConfigNotFound(ex: BalanceMonitorConfigNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(code = "BALANCE_MONITOR_CONFIG_NOT_FOUND", message = ex.message ?: "Configuração de monitoramento de saldo não encontrada", timestamp = OffsetDateTime.now()),
        )

    /** `POST`/`PUT /v1/webhooks` com `payloadTemplate` que não renderiza como JSON válido. */
    @ExceptionHandler(InvalidWebhookConfigException::class)
    fun handleInvalidWebhookConfig(ex: InvalidWebhookConfigException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_WEBHOOK_CONFIG", message = ex.message ?: "Configuração de webhook inválida", timestamp = OffsetDateTime.now()),
        )

    /** Nenhum webhook cadastrado com o id informado. */
    @ExceptionHandler(WebhookConfigNotFoundException::class)
    fun handleWebhookConfigNotFound(ex: WebhookConfigNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(code = "WEBHOOK_CONFIG_NOT_FOUND", message = ex.message ?: "Webhook não encontrado", timestamp = OffsetDateTime.now()),
        )

    /** Nenhuma carteira cadastrada com o endereço informado. */
    @ExceptionHandler(WalletNotFoundException::class)
    fun handleWalletNotFound(ex: WalletNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(code = "WALLET_NOT_FOUND", message = ex.message ?: "Carteira não encontrada", timestamp = OffsetDateTime.now()),
        )

    /** `POST /v1/wallets` com `address` já cadastrado — `address` é chave natural, unique no banco. */
    @ExceptionHandler(WalletAlreadyExistsException::class)
    fun handleWalletAlreadyExists(ex: WalletAlreadyExistsException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(code = "WALLET_ALREADY_EXISTS", message = ex.message ?: "Carteira já cadastrada", timestamp = OffsetDateTime.now()),
        )

    /** `POST`/`PUT /v1/enrichment-profiles` com `EnrichmentCall` apontando pra contrato/método/outputIndex inválido (E19/DEC-082). */
    @ExceptionHandler(InvalidEnrichmentProfileException::class)
    fun handleInvalidEnrichmentProfile(ex: InvalidEnrichmentProfileException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_ENRICHMENT_PROFILE", message = ex.message ?: "Perfil de enriquecimento inválido", timestamp = OffsetDateTime.now()),
        )

    /** Nenhum Perfil de Enriquecimento cadastrado com o id informado (E19/DEC-082). */
    @ExceptionHandler(EnrichmentProfileNotFoundException::class)
    fun handleEnrichmentProfileNotFound(ex: EnrichmentProfileNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(code = "ENRICHMENT_PROFILE_NOT_FOUND", message = ex.message ?: "Perfil de enriquecimento não encontrado", timestamp = OffsetDateTime.now()),
        )

    /** `POST`/`PUT /v1/entity-models` com `ModelContributor` apontando pra contrato/evento/parâmetro inválido (E19/DEC-082). */
    @ExceptionHandler(InvalidEntityModelException::class)
    fun handleInvalidEntityModel(ex: InvalidEntityModelException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_ENTITY_MODEL", message = ex.message ?: "Modelo de entidade inválido", timestamp = OffsetDateTime.now()),
        )

    /** Nenhum Modelo de Entidade cadastrado com o id informado (E19/DEC-082). */
    @ExceptionHandler(EntityModelNotFoundException::class)
    fun handleEntityModelNotFound(ex: EntityModelNotFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiError(code = "ENTITY_MODEL_NOT_FOUND", message = ex.message ?: "Modelo de entidade não encontrado", timestamp = OffsetDateTime.now()),
        )

    /** Preview/reenvio com mais eventos que o teto configurado (E33) — nada é processado. */
    @ExceptionHandler(TooManyEventsException::class)
    fun handleTooManyEvents(ex: TooManyEventsException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "TOO_MANY_EVENTS", message = ex.message ?: "Eventos demais na requisição", timestamp = OffsetDateTime.now()),
        )

    /** Preview/reenvio semanticamente inválido (lista de eventos vazia etc.) — E33. */
    @ExceptionHandler(InvalidWebhookRequestException::class)
    fun handleInvalidWebhookRequest(ex: InvalidWebhookRequestException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "VALIDATION_ERROR", message = ex.message ?: "Requisição inválida", timestamp = OffsetDateTime.now()),
        )

    /** Modo de bloco de enriquecimento desconhecido ou spec inválida para o modo (E33) — inclui `UnknownEnrichmentBlockModeException`. */
    @ExceptionHandler(InvalidEnrichmentBlockSpecException::class)
    fun handleInvalidEnrichmentBlockSpec(ex: InvalidEnrichmentBlockSpecException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_ENRICHMENT_BLOCK_MODE", message = ex.message ?: "Modo de bloco de enriquecimento inválido", timestamp = OffsetDateTime.now()),
        )

    /** Data não convertível em bloco (ex.: anterior ao gênesis) — E33. */
    @ExceptionHandler(BlockResolutionFailedException::class)
    fun handleBlockResolutionFailed(ex: BlockResolutionFailedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ApiError(code = "BLOCK_RESOLUTION_FAILED", message = ex.message ?: "Não foi possível resolver o bloco", timestamp = OffsetDateTime.now()),
        )

    /** `direction` de `GET /v1/blocks/by-date` desconhecida — E33. */
    @ExceptionHandler(InvalidBlockDirectionException::class)
    fun handleInvalidBlockDirection(ex: InvalidBlockDirectionException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_DIRECTION", message = ex.message ?: "direction inválida", timestamp = OffsetDateTime.now()),
        )

    /** `cursor` de `matching-events` fora do formato esperado — E33. */
    @ExceptionHandler(InvalidEventCursorException::class)
    fun handleInvalidEventCursor(ex: InvalidEventCursorException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "INVALID_CURSOR", message = ex.message ?: "Cursor inválido", timestamp = OffsetDateTime.now()),
        )

    /** Reenvio de webhook pausado sem `overrideUrl` válida (E33) — 409. */
    @ExceptionHandler(WebhookInactiveException::class)
    fun handleWebhookInactive(ex: WebhookInactiveException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiError(code = "WEBHOOK_INACTIVE", message = ex.message ?: "Webhook inativo", timestamp = OffsetDateTime.now()),
        )

    /** `overrideUrl` com a capacidade desligada (padrão) — E33/DEC-116. */
    @ExceptionHandler(UrlOverrideDisabledException::class)
    fun handleUrlOverrideDisabled(ex: UrlOverrideDisabledException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "URL_OVERRIDE_DISABLED", message = ex.message ?: "overrideUrl desabilitada", timestamp = OffsetDateTime.now()),
        )

    /** URL de destino rejeitada pela política de SSRF — mensagem genérica, nunca ecoa URL/IP (E33/DEC-116). */
    @ExceptionHandler(UrlNotAllowedException::class)
    fun handleUrlNotAllowed(ex: UrlNotAllowedException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(code = "URL_NOT_ALLOWED", message = ex.message ?: "URL de destino não permitida", timestamp = OffsetDateTime.now()),
        )

    /** Limite de taxa/fila (`RATE_LIMITED`, `RESEND_QUEUE_FULL`) — 429 com `Retry-After` em segundos (E33/DEC-116). */
    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimitExceeded(ex: RateLimitExceededException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, ex.retryAfterSeconds.toString())
            .body(ApiError(code = ex.code, message = ex.message ?: "Limite excedido", timestamp = OffsetDateTime.now()))

    /** Operação declarada no contrato mas ainda sem implementação (andaime da E33) — 501 com o [ApiError] padrão. */
    @ExceptionHandler(NotImplementedYetException::class)
    fun handleNotImplementedYet(ex: NotImplementedYetException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
            ApiError(code = "NOT_IMPLEMENTED", message = ex.message ?: "Operação ainda não implementada", timestamp = OffsetDateTime.now()),
        )

    /** `@Valid @RequestBody` inválido (ex.: POST /v1/contracts com campo obrigatório ausente). */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(
                code = "VALIDATION_ERROR",
                message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" },
                timestamp = OffsetDateTime.now(),
            ),
        )

    /**
     * `@PathVariable`/`@RequestParam` inválido (ex.: GET /v1/contracts/{address} com endereço fora do
     * formato) — exceção nativa do Spring 6.1+/7.x para validação de parâmetro de método (diferente de
     * [MethodArgumentNotValidException], que é só para corpo de requisição).
     */
    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleParameterValidation(ex: HandlerMethodValidationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(
                code = "VALIDATION_ERROR",
                message = ex.body.detail ?: "Parâmetro inválido",
                timestamp = OffsetDateTime.now(),
            ),
        )

    /**
     * `@PathVariable`/`@RequestParam` com tipo incompatível — ex.: `GET /v1/balance-monitors/{id}/...`
     * com um `id` que não parseia como UUID. Sem este handler, a resposta seria o formato de erro
     * default do Spring, não o [ApiError] que os `4xx` documentados no spec prometem (DEC-013). Vale
     * pra todos os recursos com path param tipado (contratos, balance-monitors, webhooks).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(
                code = "VALIDATION_ERROR",
                message = "${ex.name}: valor inválido para o tipo esperado",
                timestamp = OffsetDateTime.now(),
            ),
        )

    /** Fallback: caso o Spring lance a exceção Bean Validation "crua" em vez de [HandlerMethodValidationException]. */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiError(
                code = "VALIDATION_ERROR",
                message = ex.constraintViolations.joinToString("; ") { "${it.propertyPath}: ${it.message}" },
                timestamp = OffsetDateTime.now(),
            ),
        )
}
