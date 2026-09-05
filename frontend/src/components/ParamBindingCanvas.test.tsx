import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ParamBindingCanvas } from './ParamBindingCanvas'
import type { ParamBindingSlot } from './ParamBindingCanvas'
import type { ContractEventInput } from '../api/api'

const params: ContractEventInput[] = [
  { name: 'from', type: 'address', indexed: true },
  { name: 'to', type: 'address', indexed: true },
  { name: 'value', type: 'uint256', indexed: false },
]

function Harness({ onBind }: { onBind: (slotKey: string, paramName: string | null) => void }) {
  const [slots, setSlots] = useState<ParamBindingSlot[]>([
    { key: 'wallet', label: 'Carteira', kind: 'address', value: '' },
    { key: 'amount', label: 'Montante', kind: 'numeric', value: '' },
  ])
  return (
    <ParamBindingCanvas
      params={params}
      slots={slots}
      onBind={(slotKey, paramName) => {
        onBind(slotKey, paramName)
        setSlots((current) =>
          current.map((slot) => (slot.key === slotKey ? { ...slot, value: paramName ?? '' } : slot)),
        )
      }}
    />
  )
}

describe('ParamBindingCanvas', () => {
  it('vincula um parâmetro a um slot compatível pelo fallback de clique', async () => {
    const user = userEvent.setup()
    const onBind = vi.fn()
    render(<Harness onBind={onBind} />)

    await user.click(screen.getByRole('button', { name: 'Parâmetro to, tipo address' }))
    await user.click(screen.getByRole('button', { name: /Campo Carteira \(endereço\), vazio/ }))

    expect(onBind).toHaveBeenCalledWith('wallet', 'to')
    expect(screen.getByRole('button', { name: /Campo Carteira.*vinculado a to/ })).toBeInTheDocument()
  })

  it('recusa o drop quando o tipo do parâmetro não casa com o kind do slot', async () => {
    const user = userEvent.setup()
    const onBind = vi.fn()
    render(<Harness onBind={onBind} />)

    await user.click(screen.getByRole('button', { name: 'Parâmetro value, tipo uint256' }))
    await user.click(screen.getByRole('button', { name: /Campo Carteira/ }))

    expect(onBind).not.toHaveBeenCalled()
    expect(screen.getByText(/Tipo incompatível/)).toBeInTheDocument()
  })

  it('aceita um parâmetro numérico no slot numérico', async () => {
    const user = userEvent.setup()
    const onBind = vi.fn()
    render(<Harness onBind={onBind} />)

    await user.click(screen.getByRole('button', { name: 'Parâmetro value, tipo uint256' }))
    await user.click(screen.getByRole('button', { name: /Campo Montante \(numérico\), vazio/ }))

    expect(onBind).toHaveBeenCalledWith('amount', 'value')
  })

  it('desfaz um vínculo pelo botão "x" do slot', async () => {
    const user = userEvent.setup()
    const onBind = vi.fn()
    render(<Harness onBind={onBind} />)

    await user.click(screen.getByRole('button', { name: 'Parâmetro from, tipo address' }))
    await user.click(screen.getByRole('button', { name: /Campo Carteira/ }))
    expect(onBind).toHaveBeenLastCalledWith('wallet', 'from')

    await user.click(screen.getByRole('button', { name: 'Remover vínculo de Carteira' }))
    expect(onBind).toHaveBeenLastCalledWith('wallet', null)
  })

  it('mostra um aviso quando não há parâmetros (sem evento escolhido)', () => {
    render(<ParamBindingCanvas params={[]} slots={[]} onBind={vi.fn()} />)
    expect(screen.getByText(/Escolha um contrato e um evento/)).toBeInTheDocument()
  })
})
