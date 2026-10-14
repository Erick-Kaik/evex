package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Resposta resumida de `GET /api/v2/smart-contracts/{address}` (DEC-105). `abi` é `null` quando o
 * Blockscout responde 200 mas sem ABI (contrato conhecido, não verificado) — isso NÃO é erro nesta
 * porta; quem precisa da ABI decide o que fazer com `null`. [proxyType] é o `proxy_type` bruto do
 * Blockscout (`null`/`unknown` = não é proxy reconhecido). [implementations] são os endereços de
 * implementação informados (vazio se não é proxy).
 */
data class BlockscoutContractInfo(
    val name: String?,
    val abi: String?,
    val proxyType: String?,
    val implementations: List<String>,
)

/**
 * Um log `Upgraded` devolvido pela API de logs do Blockscout. [logIndex] pode faltar. [proxyAddress]
 * (lowercase) é o emissor do log — preenchido só por `getAllUpgradeLogs`; `getUpgradeHistory(proxy)`
 * deixa `null` (o proxy já é conhecido por quem chama).
 */
data class BlockscoutUpgradeLog(
    val implementationAddress: String,
    val blockNumber: Long,
    val logIndex: Long?,
    val txHash: String,
    val proxyAddress: String? = null,
)

/** Histórico de `Upgraded` de um proxy; [truncated] = o limite de resultados da API foi atingido. */
data class BlockscoutUpgradeHistory(
    val entries: List<BlockscoutUpgradeLog>,
    val truncated: Boolean,
)
