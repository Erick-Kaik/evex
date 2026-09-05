import { useState } from 'react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/renderWithProviders'
import { ContractAutocomplete } from './ContractAutocomplete'
import type { ContractRef } from './ContractAutocomplete'
import { CONTRACT_CATALOG_PAGE_SIZE } from './contractCatalog'

const { listMonitoredContracts } = vi.hoisted(() => ({ listMonitoredContracts: vi.fn() }))

vi.mock('../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts })),
  }
})

const contracts = [
  { id: '1', address: '0xaaa0000000000000000000000000000000000001', name: 'Token A', network: 'TESTNET', status: 'ACTIVE' },
  { id: '2', address: '0xbbb0000000000000000000000000000000000002', name: 'Vault B', network: 'MAINNET', status: 'ACTIVE' },
]

function Harness() {
  const [value, setValue] = useState<ContractRef | null>(null)
  return (
    <>
      <span data-testid="value">{value ? `${value.address}|${value.network}` : 'none'}</span>
      <ContractAutocomplete value={value} onChange={setValue} />
    </>
  )
}

describe('ContractAutocomplete', () => {
  beforeEach(() => {
    listMonitoredContracts.mockReset()
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 2, totalPages: 1, content: contracts },
    })
  })

  it('lista os contratos com nome, endereço e rede', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(await screen.findByRole('combobox', { name: 'Contrato' }))

    const optionA = await screen.findByRole('option', { name: /Token A/ })
    expect(optionA).toHaveTextContent('0xaaa0000000000000000000000000000000000001')
    expect(optionA).toHaveTextContent('TESTNET')
    expect(screen.getByRole('option', { name: /Vault B/ })).toHaveTextContent('MAINNET')
  })

  it('resolve para { address, network } ao selecionar', async () => {
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(await screen.findByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Vault B/ }))

    expect(screen.getByTestId('value')).toHaveTextContent(
      '0xbbb0000000000000000000000000000000000002|MAINNET',
    )
  })

  it('pede a listagem com o teto de `size` do backend (sem paginar na PoC)', async () => {
    renderWithProviders(<Harness />)
    await screen.findByRole('combobox', { name: 'Contrato' })
    expect(listMonitoredContracts).toHaveBeenCalledWith(undefined, 0, CONTRACT_CATALOG_PAGE_SIZE)
    expect(CONTRACT_CATALOG_PAGE_SIZE).toBeLessThanOrEqual(200)
  })
})
