import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WebhookDelivery } from '../../api/api'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WebhookDeliveriesSection } from './WebhookDeliveriesSection'

const { listWebhookDeliveries } = vi.hoisted(() => ({ listWebhookDeliveries: vi.fn() }))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  class WebhooksApi {
    listWebhookDeliveries = listWebhookDeliveries
  }
  return { ...actual, WebhooksApi }
})

const MESSAGE = 'eth_call em 0x…01.totalSupply (bloco 10) não retornou resultado (code=-32603): Internal error'

function delivery(over: Partial<WebhookDelivery>): WebhookDelivery {
  return {
    id: 'd-1',
    webhookConfigId: 'wh-1',
    blockchainEventId: 'e-1',
    payload: '{}',
    status: 'DELIVERED',
    attemptCount: 1,
    createdAt: '2026-02-01T00:00:00Z',
    attempts: [],
    ...over,
  }
}

function pageOf(content: WebhookDelivery[]) {
  return { data: { page: 0, size: 20, totalElements: content.length, totalPages: 1, content } }
}

beforeEach(() => listWebhookDeliveries.mockReset())

describe('WebhookDeliveriesSection', () => {
  it('mostra origem Reenvio/Normal, bloco/modelo/URL de teste redigida e, por tentativa, payload enviado e erro bruto do enriquecimento', async () => {
    listWebhookDeliveries.mockResolvedValue(
      pageOf([
        delivery({
          id: 'd-resend',
          origin: 'RESEND',
          resend: true,
          enrichmentBlockMode: 'BLOCK_NUMBER',
          enrichmentBlockNumber: 10,
          modelStateAt: '2026-10-01T12:00:00Z',
          targetUrlOverride: 'https://example.invalid',
          attempts: [
            {
              id: 'a-1',
              deliveryId: 'd-resend',
              attemptNumber: 1,
              attemptedAt: '2026-02-01T00:00:00Z',
              success: true,
              httpStatus: 200,
              enrichmentBlockUsed: '10',
              sentPayload: '{"supply":null}',
              enrichmentResults: [
                {
                  profileId: 'p1',
                  targetContractAddress: '0x0000000000000000000000000000000000000001',
                  methodName: 'totalSupply',
                  status: 'ERROR',
                  code: 'RPC_ERROR',
                  message: MESSAGE,
                  rpcCode: -32603,
                  classification: 'NODE_ERROR',
                  blockUsed: '10',
                },
              ],
            },
          ],
        }),
        delivery({ id: 'd-normal', origin: 'MATCH' }),
        delivery({ id: 'd-new', origin: 'ORIGEM_NOVA' }),
      ]),
    )
    renderWithProviders(<WebhookDeliveriesSection webhookId="wh-1" />)
    expect(await screen.findByText('Reenvio')).toBeInTheDocument()
    expect(screen.getByText('Normal')).toBeInTheDocument()
    expect(screen.getByText('ORIGEM_NOVA')).toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: 'ver tentativas' })[0])
    expect(screen.getByText('Bloco do enriquecimento: Bloco específico (10)')).toBeInTheDocument()
    expect(screen.getByText(/Modelo em:/)).toBeInTheDocument()
    expect(screen.getByText('URL de teste: https://example.invalid')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'detalhes' }))
    expect(screen.getByText(/"supply": null/)).toBeInTheDocument()
    expect(screen.getByText(/bloco usado: 10/)).toBeInTheDocument()
    expect(screen.getByText(MESSAGE)).toBeInTheDocument()
    expect(screen.getByText('rpcCode: -32603')).toBeInTheDocument()
    expect(screen.getByText('code: RPC_ERROR')).toBeInTheDocument()
  })

  it('entrega antiga sem campos novos não quebra (nulos ausentes)', async () => {
    listWebhookDeliveries.mockResolvedValue(
      pageOf([
        delivery({
          attempts: [{ id: 'a-1', deliveryId: 'd-1', attemptNumber: 1, attemptedAt: '2026-02-01T00:00:00Z', success: false, errorMessage: 'timeout' }],
        }),
      ]),
    )
    renderWithProviders(<WebhookDeliveriesSection webhookId="wh-1" />)
    expect(await screen.findByText('Normal')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'ver tentativas' }))
    const row = screen.getByText('timeout').closest('tr') as HTMLElement
    expect(within(row).queryByRole('button', { name: 'detalhes' })).not.toBeInTheDocument()
    expect(screen.queryByText(/URL de teste/)).not.toBeInTheDocument()
  })

  it('tentativa só com sentPayload (sem enrichmentResults) mostra aviso de ausência de enriquecimento', async () => {
    listWebhookDeliveries.mockResolvedValue(
      pageOf([
        delivery({
          attempts: [{ id: 'a-1', deliveryId: 'd-1', attemptNumber: 1, attemptedAt: '2026-02-01T00:00:00Z', success: true, sentPayload: '{"a":1}' }],
        }),
      ]),
    )
    renderWithProviders(<WebhookDeliveriesSection webhookId="wh-1" />)
    await userEvent.click(await screen.findByRole('button', { name: 'ver tentativas' }))
    await userEvent.click(screen.getByRole('button', { name: 'detalhes' }))
    expect(screen.getByText(/"a": 1/)).toBeInTheDocument()
    expect(screen.getByText(/Sem resultados de enriquecimento/)).toBeInTheDocument()
  })

  it('estado vazio', async () => {
    listWebhookDeliveries.mockResolvedValue(pageOf([]))
    renderWithProviders(<WebhookDeliveriesSection webhookId="wh-1" />)
    expect(await screen.findByText('Nenhuma entrega registrada ainda.')).toBeInTheDocument()
  })
})
