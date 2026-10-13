import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WebhookFormPage } from './WebhookFormPage'

const { getWebhook, listWebhookDeliveries, listWebhookMatchingEvents, getCapabilities } = vi.hoisted(() => ({
  getWebhook: vi.fn(),
  listWebhookDeliveries: vi.fn(),
  listWebhookMatchingEvents: vi.fn(),
  getCapabilities: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  class WebhooksApi {
    getWebhook = getWebhook
    listWebhookDeliveries = listWebhookDeliveries
    listWebhookMatchingEvents = listWebhookMatchingEvents
  }
  class CapabilitiesApi {
    getCapabilities = getCapabilities
  }
  class ContractsApi {
    listContracts = vi.fn().mockResolvedValue({ data: { content: [], page: 0, size: 100, totalElements: 0, totalPages: 1 } })
  }
  return { ...actual, WebhooksApi, CapabilitiesApi, ContractsApi }
})

const WEBHOOK = {
  id: 'wh-1',
  contractAddress: '0x0000000000000000000000000000000000000001',
  network: 'TESTNET',
  eventName: 'ValueChanged',
  url: 'https://example.invalid/hook',
  filters: [],
  active: true,
  createdAt: '2026-02-01T00:00:00Z',
}

function renderPage(mode: 'view' | 'edit') {
  return renderWithProviders(
    <Routes>
      <Route path="/webhooks/:id" element={<WebhookFormPage mode={mode} />} />
    </Routes>,
    { initialEntries: ['/webhooks/wh-1'] },
  )
}

beforeEach(() => {
  getWebhook.mockResolvedValue({ data: WEBHOOK })
  listWebhookDeliveries.mockResolvedValue({ data: { page: 0, size: 20, totalElements: 0, totalPages: 1, content: [] } })
  listWebhookMatchingEvents.mockResolvedValue({ data: { content: [], scanned: 0, scanTruncated: false } })
  getCapabilities.mockResolvedValue({ data: { webhookUrlOverrideEnabled: false, webhookResendMaxEventsPerRequest: 50, webhookPreviewMaxEvents: 5 } })
})

describe('WebhookFormPage — pré-visualizar e reenviar', () => {
  it('no modo view mostra os botões e abre o diálogo dedicado', async () => {
    renderPage('view')
    await userEvent.click(await screen.findByRole('button', { name: 'Reenviar' }))
    expect(await screen.findByRole('dialog', { name: 'Reenviar webhook' })).toBeInTheDocument()
    expect(screen.getByText(/Nenhum evento que case com este webhook/)).toBeInTheDocument()
  })

  it('o botão Pré-visualizar abre o diálogo com o título de pré-visualização', async () => {
    renderPage('view')
    await userEvent.click(await screen.findByRole('button', { name: 'Pré-visualizar' }))
    expect(await screen.findByRole('dialog', { name: 'Pré-visualizar webhook' })).toBeInTheDocument()
  })

  it('no modo edit os botões não aparecem', async () => {
    renderPage('edit')
    await screen.findByText('Webhook')
    expect(screen.queryByRole('button', { name: 'Reenviar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pré-visualizar' })).not.toBeInTheDocument()
  })
})
