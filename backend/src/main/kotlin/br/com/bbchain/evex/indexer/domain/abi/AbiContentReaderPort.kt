package br.com.bbchain.evex.indexer.domain.abi

/**
 * Porta de saída: lê o conteúdo bruto (JSON) de uma versão de ABI já cadastrada, resolvendo
 * internamente a Strategy de storage (`DATABASE`/`LOCAL`/`HYBRID`) pelo `storageType` do próprio
 * [ContractAbi]. É a versão "alto nível" de `AbiStorageResolver.resolve(type).retrieve(location)`,
 * exposta ao `application/` pra que o use case do catálogo de eventos não precise importar o
 * `AbiStorageResolver` concreto de `adapters/outbound/abi/` (ele não é uma porta).
 *
 * Implementação: `adapters/outbound/abi/AbiContentReader` (compõe `AbiStorageResolver` +
 * [AbiStoragePort.retrieve]).
 *
 * Nota estrutural (DEC-076): `AbiCache.warmUp` e `AbiRegistrationService.register` fazem hoje esse
 * mesmo par resolver+retrieve importando o `AbiStorageResolver` concreto — poderiam ser repontados
 * pra esta porta num passo de limpeza futuro. Não é obrigatório na E16 (fora do escopo do endpoint).
 */
interface AbiContentReaderPort {
    /** Conteúdo JSON da versão [abi]. Lança se o storage estiver inacessível / o ponteiro inválido. */
    fun read(abi: ContractAbi): String
}
