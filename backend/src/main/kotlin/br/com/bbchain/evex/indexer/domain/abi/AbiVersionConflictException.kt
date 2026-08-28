package br.com.bbchain.evex.indexer.domain.abi

/**
 * Duas requisições concorrentes de cadastro de ABI (`POST /v1/contracts/{address}/abi`) pro mesmo
 * endereço colidiram na unique constraint parcial de banco `contract_abis_unique_open_version`
 * (`db/migration/V1__contract_abis_unique_open_version.sql`, DEC-022) — no máximo 1 versão "aberta"
 * (`valid_to_block IS NULL`) por `contract_address`. [AbiRegistrationService] traduz a
 * `DataIntegrityViolationException` (Spring/JPA) pra esta exceção de domínio antes dela escapar do
 * caso de uso — mesmo padrão já usado pra `ContractAlreadyExistsException` (E2), mas como exceção
 * dedicada (não reaproveitada) porque o `@ExceptionHandler` de
 * `DataIntegrityViolationException` já está ocupado por essa outra constraint (`monitored_contracts.
 * address`) e o Spring não permite 2 handlers pro mesmo tipo de exceção num único `@RestControllerAdvice`.
 */
class AbiVersionConflictException(
    val address: String,
) : RuntimeException(
    "Já existe uma versão de ABI aberta para $address sendo cadastrada concorrentemente — tente novamente.",
)
