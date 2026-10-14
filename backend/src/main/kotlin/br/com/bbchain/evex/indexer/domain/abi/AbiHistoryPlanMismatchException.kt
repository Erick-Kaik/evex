package br.com.bbchain.evex.indexer.domain.abi

/**
 * `expectedFingerprint` do import difere do fingerprint do plano recalculado no servidor: o estado
 * (upgrades, versões cadastradas ou ABIs no Blockscout) mudou desde o dry-run. Nada foi aplicado.
 */
class AbiHistoryPlanMismatchException(
    val address: String,
    val expected: String,
    val current: String,
) : RuntimeException(
    "O plano de importação de ABI de $address mudou desde o dry-run (fingerprint esperado $expected, atual $current); " +
        "revise o plano novamente antes de importar.",
)
