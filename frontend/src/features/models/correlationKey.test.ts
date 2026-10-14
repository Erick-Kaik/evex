import { describe, expect, it } from 'vitest'
import type { ModelContributor } from '../../api/api'
import { describeFieldSource, effectiveKeyParts, keyChanged, previewKey, validateCorrelationKeys } from './correlationKey'

const base = { contractAddress: '0xbbbb000000000000000000000000000000000002', eventName: 'Created', fieldMappings: [] }

describe('correlationKey helpers', () => {
  it('modelo legado vira 1 parte PARAM; correlationKey não vazia vence', () => {
    expect(effectiveKeyParts({ ...base, correlationParam: 'id' })).toEqual([{ type: 'PARAM', arg: 'id' }])
    expect(
      effectiveKeyParts({ ...base, correlationParam: 'id', correlationKey: [{ type: 'TX_HASH' }] }),
    ).toEqual([{ type: 'TX_HASH' }])
  })

  it('pré-visualização textual, inclusive tipo desconhecido cru', () => {
    expect(previewKey([{ type: 'EMITTER_ADDRESS' }, { type: 'PARAM', arg: 'id' }])).toBe('EMITTER_ADDRESS | PARAM(id)')
    expect(previewKey([{ type: 'LITERAL', arg: 'x' }, { type: 'FUTURE_TYPE', arg: 'z' }])).toBe('LITERAL("x") | FUTURE_TYPE(z)')
    expect(previewKey([])).toBe('(sem chave)')
  })

  it('validação: sem partes, arg faltando e aridade diferente', () => {
    const one: ModelContributor = { ...base, correlationKey: [{ type: 'TX_HASH' }] }
    const two: ModelContributor = { ...base, correlationKey: [{ type: 'EMITTER_ADDRESS' }, { type: 'PARAM', arg: 'id' }] }
    expect(validateCorrelationKeys([one])).toEqual([])
    expect(validateCorrelationKeys([base])).toHaveLength(1)
    expect(validateCorrelationKeys([{ ...base, correlationKey: [{ type: 'LITERAL', arg: ' ' }] }])[0]).toMatch(/LITERAL exige/)
    expect(validateCorrelationKeys([one, two])[0]).toMatch(/mesmo número de partes/)
    // legado conta como 1 parte
    expect(validateCorrelationKeys([{ ...base, correlationParam: 'id' }, one])).toEqual([])
  })

  it('keyChanged só considera contribuintes já existentes', () => {
    const original = [{ ...base, correlationParam: 'id' }]
    expect(keyChanged(original, original)).toBe(false)
    expect(keyChanged(original, [{ ...base, correlationKey: [{ type: 'PARAM', arg: 'id' }] }])).toBe(false)
    expect(keyChanged(original, [{ ...base, correlationKey: [{ type: 'TX_HASH' }] }])).toBe(true)
    expect(keyChanged(original, [{ ...base, eventName: 'Other', correlationKey: [{ type: 'TX_HASH' }] }])).toBe(false)
  })

  it('fonte do campo: PARAM mostra o parâmetro; outras, o código cru', () => {
    expect(describeFieldSource({ decodedParam: 'owner' })).toBe('owner')
    expect(describeFieldSource({ source: 'EMITTER_ADDRESS' })).toBe('EMITTER_ADDRESS')
    expect(describeFieldSource({ source: 'NEW_SOURCE' })).toBe('NEW_SOURCE')
  })
})
