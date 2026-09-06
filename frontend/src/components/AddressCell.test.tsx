import { describe, expect, it, vi, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import { AddressCell } from './AddressCell'

const { listMonitoredContracts, listWallets } = vi.hoisted(() => ({
  listMonitoredContracts: vi.fn(),
  listWallets: vi.fn(),
}))

vi.mock('../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/api')>()
  return {
    ...actual,
    ContractsApi: vi.fn(() => ({ listMonitoredContracts })),
    WalletsApi: vi.fn(() => ({ listWallets })),
  }
})

const KNOWN_CONTRACT = '0xaaa0000000000000000000000000000000000001'
const UNKNOWN_CONTRACT = '0xbbb0000000000000000000000000000000000002'
const KNOWN_WALLET = '0xccc0000000000000000000000000000000000003'
const UNKNOWN_WALLET = '0xddd0000000000000000000000000000000000004'

describe('AddressCell', () => {
  beforeEach(() => {
    listMonitoredContracts.mockReset()
    listWallets.mockReset()
    listMonitoredContracts.mockResolvedValue({
      data: {
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
        content: [{ id: '1', address: KNOWN_CONTRACT, name: 'Token A', network: 'TESTNET', status: 'ACTIVE' }],
      },
    })
    listWallets.mockResolvedValue({
      data: {
        page: 0,
        size: 200,
        totalElements: 1,
        totalPages: 1,
        content: [
          {
            id: 'w1',
            address: KNOWN_WALLET,
            label: 'Carteira A',
            network: 'TESTNET',
            status: 'ACTIVE',
            looksLikeContract: false,
          },
        ],
      },
    })
  })

  it('contrato cadastrado: mostra nome com link + endereço abreviado embaixo', async () => {
    renderWithProviders(<AddressCell address={KNOWN_CONTRACT} kind="contract" />)

    const link = await screen.findByRole('link', { name: 'Token A' })
    expect(link).toHaveAttribute('href', `/contracts/${KNOWN_CONTRACT}`)
    expect(screen.getByText('0xaaa000…0001')).toBeInTheDocument()
  })

  it('contrato não cadastrado: mantém endereço abreviado + tooltip, sem nome', async () => {
    renderWithProviders(<AddressCell address={UNKNOWN_CONTRACT} kind="contract" />)

    await screen.findByText('0xbbb000…0002')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('carteira cadastrada: mostra label com link + endereço abreviado embaixo', async () => {
    renderWithProviders(<AddressCell address={KNOWN_WALLET} kind="wallet" />)

    const link = await screen.findByRole('link', { name: 'Carteira A' })
    expect(link).toHaveAttribute('href', `/wallets/${KNOWN_WALLET}`)
    expect(screen.getByText('0xccc000…0003')).toBeInTheDocument()
  })

  it('carteira não cadastrada: mantém endereço abreviado, sem nome', async () => {
    renderWithProviders(<AddressCell address={UNKNOWN_WALLET} kind="wallet" />)

    await screen.findByText('0xddd000…0004')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
