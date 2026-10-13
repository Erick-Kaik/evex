package br.com.bbchain.evex.indexer.domain.abi

/** Assinatura canônica `nome(tipo,tipo)` de um método — sem espaços, na ordem declarada. */
fun ParsedAbiMethod.signature(): String = "$name(${inputs.joinToString(",") { it.type }})"

/**
 * Resultado da escolha de um método de ABI por nome + aridade ([AbiMethodResolver.resolve]). Nunca
 * escolhe em silêncio quando há mais de uma opção.
 */
sealed interface MethodResolution {
    /** Exatamente um método casa. */
    data class Found(val method: ParsedAbiMethod) : MethodResolution

    /** Nenhum método com esse nome (ou assinatura). */
    data class NameNotFound(val requested: String) : MethodResolution

    /** Existe método com o nome, mas nenhuma sobrecarga com a aridade pedida. */
    data class ArityMismatch(val requested: String, val arity: Int, val availableSignatures: List<String>) : MethodResolution

    /** Mais de uma sobrecarga distinta com o mesmo nome E a mesma aridade (ex.: `f(uint256)` e `f(address)`). */
    data class Ambiguous(val requested: String, val candidates: List<String>) : MethodResolution
}

/**
 * Resolução pura (domínio) de método por **nome + aridade** (`inputs.size == arity`).
 *
 * Antes desta regra o enriquecimento escolhia o primeiro método pelo nome e o `zip` de tipos/argumentos
 * truncava em silêncio — sobrecarga errada ou aridade divergente geravam `eth_call` com selector
 * errado. Agora:
 *  - 1 método com o nome e a aridade bate -> [MethodResolution.Found];
 *  - o nome existe mas nenhuma sobrecarga tem a aridade -> [MethodResolution.ArityMismatch] (erro claro,
 *    **não** mais truncamento silencioso — o antigo comportamento era um bug);
 *  - 2+ sobrecargas distintas com a mesma aridade -> [MethodResolution.Ambiguous]. Desambiguação: o
 *    chamador pode informar a assinatura canônica no lugar do nome (`balanceOf(address)`), que casa
 *    exato (e sua aridade tem que bater com [arity]).
 *
 * Assinaturas idênticas repetidas (mesmo nome + tipos) contam como um método só.
 */
object AbiMethodResolver {
    fun resolve(methods: List<ParsedAbiMethod>, methodName: String, arity: Int): MethodResolution {
        val distinct = methods.distinctBy { it.signature() }
        val requestedSignature = methodName.takeIf { it.contains('(') }?.filterNot { it.isWhitespace() }

        val sameName = if (requestedSignature != null) {
            distinct.filter { it.signature() == requestedSignature }
        } else {
            distinct.filter { it.name == methodName }
        }
        if (sameName.isEmpty()) {
            // Com assinatura informada, ainda diferenciar "o nome existe" de "nada com esse nome".
            val bareName = requestedSignature?.substringBefore('(') ?: methodName
            val sameBareName = distinct.filter { it.name == bareName }
            return if (sameBareName.isEmpty()) {
                MethodResolution.NameNotFound(methodName)
            } else {
                MethodResolution.ArityMismatch(methodName, arity, sameBareName.map { it.signature() })
            }
        }
        val sameArity = sameName.filter { it.inputs.size == arity }
        return when (sameArity.size) {
            0 -> MethodResolution.ArityMismatch(methodName, arity, sameName.map { it.signature() })
            1 -> MethodResolution.Found(sameArity.single())
            else -> MethodResolution.Ambiguous(methodName, sameArity.map { it.signature() })
        }
    }
}
