package br.com.bbchain.evex.indexer.domain.abi

/**
 * Origem do cadastro de uma [ContractAbi] (doc de produto §4.1, campo `source`).
 * `BLOCKSCOUT`/`AUTO_DETECTED` só passam a ter comportamento próprio em E6 (discovery Blockscout +
 * detecção automática de interface) — nesta etapa (E3) só `UPLOAD_FILE`/`UPLOAD_TEXT` chegam pelo
 * mesmo campo `abi` (string) do request, diferenciados só por esta tag.
 */
enum class AbiSource {
    UPLOAD_FILE,
    UPLOAD_TEXT,
    BLOCKSCOUT,
    AUTO_DETECTED,
}
