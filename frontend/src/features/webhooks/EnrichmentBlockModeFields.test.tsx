import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { DEFAULT_BLOCK_MODE, blockModeError, toBlockSpec } from './blockMode'
import type { BlockModeValue } from './blockMode'
import { EnrichmentBlockModeFields } from './EnrichmentBlockModeFields'

function Harness({ initial = DEFAULT_BLOCK_MODE }: { initial?: BlockModeValue }) {
  const [value, setValue] = useState(initial)
  return <EnrichmentBlockModeFields value={value} onChange={setValue} />
}

describe('EnrichmentBlockModeFields', () => {
  it('EVENT_BLOCK (padrão) não mostra campos extras e traz a ajuda honesta sobre -32603', () => {
    render(<Harness />)
    expect(screen.queryByLabelText('Número do bloco')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Data/hora do bloco')).not.toBeInTheDocument()
    expect(screen.getByText(/não prova que o nó não guarda histórico/)).toBeInTheDocument()
  })

  it('BLOCK_NUMBER mostra o campo numérico', () => {
    render(<Harness initial={{ ...DEFAULT_BLOCK_MODE, mode: 'BLOCK_NUMBER' }} />)
    expect(screen.getByLabelText('Número do bloco')).toBeInTheDocument()
  })

  it('BLOCK_AT_DATE mostra data/hora com o fuso e avisa da resolução aproximada', () => {
    render(<Harness initial={{ ...DEFAULT_BLOCK_MODE, mode: 'BLOCK_AT_DATE' }} />)
    expect(screen.getByLabelText('Data/hora do bloco')).toBeInTheDocument()
    expect(screen.getByText(/Fuso:/)).toBeInTheDocument()
    expect(screen.getByText(/resolução é aproximada/)).toBeInTheDocument()
  })

  it('troca de modo via select e valida/convert o valor', async () => {
    const onChange = vi.fn()
    render(<EnrichmentBlockModeFields value={DEFAULT_BLOCK_MODE} onChange={onChange} />)
    await userEvent.click(screen.getByRole('combobox', { name: 'Bloco do enriquecimento' }))
    await userEvent.click(screen.getByRole('option', { name: 'Estado atual (latest)' }))
    expect(onChange).toHaveBeenCalledWith({ ...DEFAULT_BLOCK_MODE, mode: 'LATEST' })

    expect(blockModeError({ ...DEFAULT_BLOCK_MODE, mode: 'BLOCK_NUMBER', blockNumber: 'x' })).toMatch(/número do bloco/)
    expect(toBlockSpec({ ...DEFAULT_BLOCK_MODE, mode: 'BLOCK_NUMBER', blockNumber: '42' })).toEqual({ mode: 'BLOCK_NUMBER', blockNumber: 42 })
    expect(blockModeError({ ...DEFAULT_BLOCK_MODE, mode: 'BLOCK_AT_DATE' })).toMatch(/data/)
    expect(toBlockSpec(DEFAULT_BLOCK_MODE)).toEqual({ mode: 'EVENT_BLOCK' })
  })
})
