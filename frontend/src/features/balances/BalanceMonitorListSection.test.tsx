import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import { renderWithProviders } from '../../test/renderWithProviders'
import { BalanceMonitorListSection } from './BalanceMonitorListSection'

const { listBalanceMonitors, listMonitoredContracts } = vi.hoisted(() => ({
  listBalanceMonitors: vi.fn(),
  listMonitoredContracts: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    BalanceMonitorsApi: vi.fn(() => ({ listBalanceMonitors })),
    ContractsApi: vi.fn(() => ({ listMonitoredContracts })),
  }
})

const KNOWN = '0xaaa0000000000000000000000000000000000001'
const UNKNOWN = '0xbbb0000000000000000000000000000000000002'

describe('BalanceMonitorListSection', () => {
  it('mostra nome+link do contrato quando cadastrado, e endereço curto quando não (mesmo padrão de AddressCell)', async () => {
    listMonitoredContracts.mockResolvedValue({
      data: { content: [{ address: KNOWN, name: 'Token A', network: 'TESTNET', status: 'ACTIVE' }] },
    })
    listBalanceMonitors.mockResolvedValue({
      data: {
        page: 0,
        size: 20,
        totalElements: 2,
        totalPages: 1,
        content: [
          {
            id: 'm1',
            contractAddress: KNOWN,
            network: 'TESTNET',
            eventName: 'Transfer',
            behavior: 'CREDIT',
            walletParam: 'to',
            valueParam: 'value',
            active: true,
          },
          {
            id: 'm2',
            contractAddress: UNKNOWN,
            network: 'TESTNET',
            eventName: 'TransferSingle',
            behavior: 'CREDIT',
            walletParam: 'to',
            valueParam: 'value',
            active: true,
          },
        ],
      },
    })

    renderWithProviders(<BalanceMonitorListSection />)

    const link = await screen.findByRole('link', { name: 'Token A' })
    expect(link).toHaveAttribute('href', `/contracts/${KNOWN}`)

    const headerRow = screen.getAllByRole('row')[0]
    expect(within(headerRow).getByText('Contrato')).toBeInTheDocument()

    // contrato não cadastrado -> endereço encurtado, sem link
    expect(screen.getByText('0xbbb000…0002')).toBeInTheDocument()
  })
})
