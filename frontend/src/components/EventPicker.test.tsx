import { useState } from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/renderWithProviders'
import { EventPicker } from './EventPicker'
import type { ContractEventInput } from '../api/api'

const { listContractEvents } = vi.hoisted(() => ({ listContractEvents: vi.fn() }))

vi.mock('../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listContractEvents })),
  }
})

const ADDRESS = '0xabc0000000000000000000000000000000000001'

function Harness({ address, initialValue = '' }: { address?: string; initialValue?: string }) {
  const [value, setValue] = useState(initialValue)
  const [inputs, setInputs] = useState<ContractEventInput[]>([])
  return (
    <>
      <span data-testid="value">{value}</span>
      <span data-testid="inputs">{inputs.map((i) => i.name).join(',')}</span>
      <EventPicker
        address={address}
        value={value}
        onChange={(name, nextInputs) => {
          setValue(name)
          setInputs(nextInputs)
        }}
      />
    </>
  )
}

describe('EventPicker', () => {
  beforeEach(() => listContractEvents.mockReset())

  it('fica desabilitado sem endereço', () => {
    renderWithProviders(<Harness />)
    expect(screen.getByRole('combobox', { name: 'Nome do evento' })).toBeDisabled()
    expect(listContractEvents).not.toHaveBeenCalled()
  })

  it('catálogo vazio (200) → CTA de cadastrar ABI, não erro', async () => {
    listContractEvents.mockResolvedValue({ data: [] })
    renderWithProviders(<Harness address={ADDRESS} />)

    expect(await screen.findByText(/não tem ABI cadastrada/i)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /Cadastrar ABI/i })
    expect(link).toHaveAttribute('href', `/contracts/${ADDRESS}`)
  })

  it('aceita um value fora do catálogo (evento de ABI antiga) sem esvaziar o campo', async () => {
    listContractEvents.mockResolvedValue({
      data: [{ name: 'Transfer', inputs: [], validFromBlock: 0, validToBlock: null }],
    })
    renderWithProviders(<Harness address={ADDRESS} initialValue="LegacyEvent" />)

    const field = await screen.findByRole('combobox', { name: 'Nome do evento' })
    expect(field).toHaveValue('LegacyEvent')
    expect(screen.getByText(/não está no catálogo do contrato/i)).toBeInTheDocument()
  })

  it('mostra o CTA de cadastrar ABI só quando não há value atual', async () => {
    listContractEvents.mockResolvedValue({ data: [] })
    renderWithProviders(<Harness address={ADDRESS} initialValue="LegacyEvent" />)

    const field = await screen.findByRole('combobox', { name: 'Nome do evento' })
    expect(field).toHaveValue('LegacyEvent')
    expect(screen.queryByText(/não tem ABI cadastrada/i)).not.toBeInTheDocument()
  })

  it('de-duplica o mesmo evento vindo de faixas de bloco diferentes (proxy, DEC-076)', async () => {
    listContractEvents.mockResolvedValue({
      data: [
        {
          name: 'Transfer',
          inputs: [{ name: 'from', type: 'address', indexed: true }],
          validFromBlock: 0,
          validToBlock: 99,
        },
        {
          name: 'Transfer',
          inputs: [
            { name: 'from', type: 'address', indexed: true },
            { name: 'to', type: 'address', indexed: true },
            { name: 'value', type: 'uint256', indexed: false },
          ],
          validFromBlock: 100,
          validToBlock: null,
        },
        {
          name: 'Approval',
          inputs: [{ name: 'owner', type: 'address', indexed: true }],
          validFromBlock: 100,
          validToBlock: null,
        },
      ],
    })
    const user = userEvent.setup()
    renderWithProviders(<Harness address={ADDRESS} />)

    await user.click(await screen.findByRole('combobox', { name: 'Nome do evento' }))
    const listbox = await screen.findByRole('listbox')
    const options = within(listbox).getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['Transfer', 'Approval'])

    await user.click(within(listbox).getByRole('option', { name: 'Transfer' }))
    // inputs mesclados = os da faixa mais recente (validToBlock null)
    expect(screen.getByTestId('value')).toHaveTextContent('Transfer')
    expect(screen.getByTestId('inputs')).toHaveTextContent('from,to,value')
  })
})
