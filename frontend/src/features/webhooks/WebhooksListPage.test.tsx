import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WebhooksListPage } from './WebhooksListPage'

const { listWebhooks, listMonitoredContracts } = vi.hoisted(() => ({
  listWebhooks: vi.fn(),
  listMonitoredContracts: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  return {
    ...actual,
    WebhooksApi: vi.fn(() => ({ listWebhooks })),
    ContractsApi: vi.fn(() => ({ listMonitoredContracts })),
  }
})

const KNOWN = '0xaaa0000000000000000000000000000000000001'
const UNKNOWN = '0xbbb0000000000000000000000000000000000002'

describe('WebhooksListPage', () => {
  it('mostra nome+link do contrato quando cadastrado, e endereço curto quando não (mesmo padrão de AddressCell)', async () => {
    listMonitoredContracts.mockResolvedValue({
      data: { content: [{ address: KNOWN, name: 'Token A', network: 'TESTNET', status: 'ACTIVE' }] },
    })
    listWebhooks.mockResolvedValue({
      data: {
        page: 0,
        size: 20,
        totalElements: 2,
        totalPages: 1,
        content: [
          {
            id: 'w1',
            contractAddress: KNOWN,
            network: 'TESTNET',
            eventName: 'Transfer',
            url: 'https://webhook.site/a',
            filters: [],
            active: true,
            createdAt: '2026-01-01T00:00:00Z',
            enrichmentProfileIds: [],
            entityModelIds: [],
          },
          {
            id: 'w2',
            contractAddress: UNKNOWN,
            network: 'TESTNET',
            eventName: 'Deposited',
            url: 'https://webhook.site/b',
            filters: [],
            active: true,
            createdAt: '2026-01-01T00:00:00Z',
            enrichmentProfileIds: [],
            entityModelIds: [],
          },
        ],
      },
    })

    renderWithProviders(<WebhooksListPage />)

    const link = await screen.findByRole('link', { name: 'Token A' })
    expect(link).toHaveAttribute('href', `/contracts/${KNOWN}`)

    const headerRow = screen.getAllByRole('row')[0]
    expect(within(headerRow).getByText('Contrato')).toBeInTheDocument()

    // contrato não cadastrado -> endereço encurtado, sem link
    expect(screen.getByText('0xbbb000…0002')).toBeInTheDocument()
  })
})
