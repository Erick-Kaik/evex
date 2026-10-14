package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Cobertura do complemento de logs `Upgraded` do Blockscout numa classificação do discovery (DEC-107).
 * Só altera os avisos do [ProxyClassifier], nunca a regra de classificação.
 */
enum class UpgradeLogsCoverage {
    /** Complemento desligado ou não solicitado: só eventos ingeridos (comportamento da DEC-104). */
    NOT_CONSULTED,

    /** Lista de logs do Blockscout obtida por inteiro. */
    COMPLETE,

    /** Lista do Blockscout possivelmente incompleta (teto de páginas / muitos logs num bloco). */
    TRUNCATED,

    /** Blockscout falhou ao listar os logs; classificação só com eventos ingeridos. */
    UNAVAILABLE,
}
