package br.com.bbchain.evex.indexer.domain.abi

/**
 * Nível de confiança da detecção automática de interface (doc de produto §4.5, `abi-strategy-design.md`
 * §3): `FULL` = todos os `topic0` do [InterfaceTemplate] foram observados no contrato (nível 1 do doc,
 * único aceito por [AbiSource.AUTO_DETECTED] — decisão tomada com o usuário na E6, nunca aplica um
 * match parcial automaticamente); `PARTIAL` = só parte dos `topic0` do template bateu (nível 2, vira
 * sugestão informativa em `GET .../abi/suggestions`, nunca aplicada sozinha); `NONE` = nenhum `topic0`
 * do template apareceu (nível 3, cadastro manual obrigatório).
 */
enum class DetectionLevel {
    FULL,
    PARTIAL,
    NONE,
}
