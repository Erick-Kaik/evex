import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { BalanceQuerySection } from './BalanceQuerySection'

const { useBalances, useBalanceHistory, listMonitoredContracts, listWallets } = vi.hoisted(() => ({
  useBalances: vi.fn(),
  useBalanceHistory: vi.fn(),
  listMonitoredContracts: vi.fn(),
  listWallets: vi.fn(),
}))

vi.mock('./useBalances', () => ({ useBalances, useBalanceHistory }))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts })),
    WalletsApi: vi.fn(() => ({ listWallets })),
  }
})

const CONTRACT = {
  id: '1',
  address: '0xaaa0000000000000000000000000000000000001',
  name: 'Token A',
  network: 'TESTNET',
  status: 'ACTIVE',
}

const WALLET = {
  id: 'w1',
  address: '0xwallet00000000000000000000000000000000001',
  label: 'Carteira A',
  network: 'TESTNET',
  status: 'ACTIVE',
  looksLikeContract: false,
}

const emptyResult = {
  data: { page: 0, size: 20, totalElements: 0, totalPages: 0, content: [] },
  isPending: false,
  isError: false,
  error: null,
}

describe('BalanceQuerySection', () => {
  beforeEach(() => {
    useBalances.mockReset().mockReturnValue(emptyResult)
    useBalanceHistory.mockReset().mockReturnValue(emptyResult)
    listMonitoredContracts.mockResolvedValue({
      data: { page: 0, size: 500, totalElements: 1, totalPages: 1, content: [CONTRACT] },
    })
    listWallets.mockResolvedValue({
      data: { page: 0, size: 200, totalElements: 1, totalPages: 1, content: [WALLET] },
    })
  })

  it('herda a rede do contrato escolhido no autocomplete (sem <NetworkSelect>)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BalanceQuerySection />)

    expect(screen.queryByRole('combobox', { name: 'Rede' })).not.toBeInTheDocument()

    await user.click(await screen.findByRole('combobox', { name: 'Contrato' }))
    await user.click(await screen.findByRole('option', { name: /Token A/ }))

    await user.click(await screen.findByRole('combobox', { name: 'Carteira' }))
    await user.click(await screen.findByRole('option', { name: /Carteira A/ }))

    await user.click(screen.getByRole('button', { name: 'Consultar' }))

    const lastFilters = useBalances.mock.calls.at(-1)?.[0]
    expect(lastFilters).toMatchObject({
      contractAddress: CONTRACT.address,
      network: 'TESTNET',
      walletAddress: WALLET.address,
    })
  })

  it('fallback manual: revela endereço livre + <NetworkSelect> para contrato não cadastrado', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BalanceQuerySection />)

    await user.click(screen.getByRole('button', { name: /digitar endereço do contrato manualmente/i }))

    await user.type(screen.getByRole('textbox', { name: 'Contrato' }), '0xbbb0000000000000000000000000000000000002')
    await user.click(screen.getByRole('combobox', { name: 'Rede' }))
    await user.click(await screen.findByRole('option', { name: 'MAINNET' }))

    await user.click(screen.getByRole('button', { name: 'Consultar' }))

    const lastFilters = useBalances.mock.calls.at(-1)?.[0]
    expect(lastFilters).toMatchObject({
      contractAddress: '0xbbb0000000000000000000000000000000000002',
      network: 'MAINNET',
    })
  })

  it('fallback manual: permite digitar endereço de carteira não cadastrada (E22)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<BalanceQuerySection />)

    await user.click(screen.getByRole('button', { name: /digitar endereço da carteira manualmente/i }))
    await user.type(
      screen.getByRole('textbox', { name: 'Carteira' }),
      '0xccc0000000000000000000000000000000000009',
    )

    await user.click(screen.getByRole('button', { name: 'Consultar' }))

    const lastFilters = useBalances.mock.calls.at(-1)?.[0]
    expect(lastFilters).toMatchObject({
      walletAddress: '0xccc0000000000000000000000000000000000009',
    })
  })
})
