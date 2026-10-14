import type { ModelContributor, ModelCorrelationKeyPart } from '../../api/api'

/** Tipos iniciais de parte da chave de correlação (DEC-111). O tipo é string extensível: o backend pode ganhar outros. */
export const KEY_PART_TYPES = ['PARAM', 'EMITTER_ADDRESS', 'TX_HASH', 'BLOCK_NUMBER', 'LITERAL'] as const

/** Fontes iniciais de um campo mapeado (DEC-111). Também extensível. */
export const FIELD_SOURCES = ['PARAM', 'EMITTER_ADDRESS', 'TX_HASH', 'BLOCK_NUMBER', 'BLOCK_TIMESTAMP'] as const

/** Tipos cujo `arg` é obrigatório. */
export function keyPartNeedsArg(type: string): boolean {
  return type === 'PARAM' || type === 'LITERAL'
}

/**
 * Partes efetivas da chave de um contribuinte: `correlationKey` não vazia vence (precedência do
 * backend); senão o atalho legado `correlationParam` equivale a uma parte `PARAM`.
 */
export function effectiveKeyParts(contributor: ModelContributor): ModelCorrelationKeyPart[] {
  if (contributor.correlationKey && contributor.correlationKey.length > 0) {
    return contributor.correlationKey
  }
  if (contributor.correlationParam) {
    return [{ type: 'PARAM', arg: contributor.correlationParam }]
  }
  return []
}

/** `true` quando o contribuinte usa só o atalho legado (`correlationParam`, sem `correlationKey`). */
export function isLegacyKey(contributor: ModelContributor): boolean {
  return !(contributor.correlationKey && contributor.correlationKey.length > 0) && Boolean(contributor.correlationParam)
}

/** Grava novas partes: passa a enviar `correlationKey` e omite o atalho legado (a chave nova vence). */
export function withKeyParts(contributor: ModelContributor, parts: ModelCorrelationKeyPart[]): ModelContributor {
  return { ...contributor, correlationKey: parts, correlationParam: undefined }
}

/** Texto legível de uma parte: `PARAM(id)`, `LITERAL("x")`, `EMITTER_ADDRESS`. Tipo desconhecido sai cru. */
export function describeKeyPart(part: ModelCorrelationKeyPart): string {
  if (part.type === 'LITERAL') return `LITERAL("${part.arg ?? ''}")`
  if (part.arg !== undefined && part.arg !== null && part.arg !== '') return `${part.type}(${part.arg})`
  if (part.type === 'PARAM') return 'PARAM(?)'
  return part.type
}

/** Pré-visualização da chave: `EMITTER_ADDRESS | PARAM(id)`; vazio vira `(sem chave)`. */
export function previewKey(parts: ModelCorrelationKeyPart[]): string {
  return parts.length === 0 ? '(sem chave)' : parts.map(describeKeyPart).join(' | ')
}

/** Descrição da fonte de um campo mapeado para o modo leitura. */
export function describeFieldSource(mapping: { decodedParam?: string; source?: string }): string {
  const source = mapping.source ?? 'PARAM'
  return source === 'PARAM' ? (mapping.decodedParam ?? '?') : source
}

/** Problemas da chave de correlação dos contribuintes (mesmas regras que o backend aplica com 400). */
export function validateCorrelationKeys(contributors: ModelContributor[]): string[] {
  const issues: string[] = []
  const arities = new Set<number>()
  contributors.forEach((contributor, index) => {
    const label = `Evento contribuinte ${index + 1}`
    const parts = effectiveKeyParts(contributor)
    if (parts.length === 0) {
      issues.push(`${label}: a chave de correlação precisa de ao menos 1 parte.`)
      return
    }
    arities.add(parts.length)
    parts.forEach((part, partIndex) => {
      if (keyPartNeedsArg(part.type) && !(part.arg ?? '').trim()) {
        issues.push(`${label}, parte ${partIndex + 1}: ${part.type} exige um valor.`)
      }
    })
  })
  if (arities.size > 1) {
    issues.push('Todos os eventos contribuintes precisam ter o mesmo número de partes na chave de correlação.')
  }
  return issues
}

/**
 * `true` quando a chave de algum contribuinte já existente (mesmo contrato + evento) mudou em relação
 * ao modelo salvo — editar a chave deixa os registros antigos órfãos (sem reprojeção histórica).
 */
export function keyChanged(original: ModelContributor[], current: ModelContributor[]): boolean {
  return current.some((contributor) => {
    const before = original.find(
      (candidate) =>
        candidate.contractAddress === contributor.contractAddress && candidate.eventName === contributor.eventName,
    )
    return before !== undefined && previewKey(effectiveKeyParts(before)) !== previewKey(effectiveKeyParts(contributor))
  })
}
