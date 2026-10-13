import type { EnrichmentBlockSpec } from '../../api/api'
import { toIsoWithOffset } from './resendUtils'
import type { BlockMode } from './resendUtils'

export interface BlockModeValue {
  mode: BlockMode
  /** Texto do campo numérico (modo `BLOCK_NUMBER`). */
  blockNumber: string
  /** Valor do `datetime-local` (modo `BLOCK_AT_DATE`). */
  blockDate: string
}

export const DEFAULT_BLOCK_MODE: BlockModeValue = { mode: 'EVENT_BLOCK', blockNumber: '', blockDate: '' }

/** Mensagem de validação do modo escolhido (`null` = válido). */
export function blockModeError(value: BlockModeValue): string | null {
  if (value.mode === 'BLOCK_NUMBER' && !/^\d+$/.test(value.blockNumber.trim())) {
    return 'Informe o número do bloco (inteiro não negativo).'
  }
  if (value.mode === 'BLOCK_AT_DATE' && !toIsoWithOffset(value.blockDate)) {
    return 'Informe a data/hora do bloco.'
  }
  return null
}

/** Converte o valor do formulário no `enrichmentBlock` do contrato (só os campos do modo). */
export function toBlockSpec(value: BlockModeValue): EnrichmentBlockSpec {
  switch (value.mode) {
    case 'BLOCK_NUMBER':
      return { mode: value.mode, blockNumber: Number(value.blockNumber.trim()) }
    case 'BLOCK_AT_DATE':
      return { mode: value.mode, blockDate: toIsoWithOffset(value.blockDate) }
    default:
      return { mode: value.mode }
  }
}
