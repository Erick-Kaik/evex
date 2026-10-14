import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AxiosError } from 'axios'
import type { AxiosResponse } from 'axios'
import type { Event as IndexedEvent } from '../../api/api'
import { renderWithProviders } from '../../test/renderWithProviders'
import { WebhookResendDialog } from './WebhookResendDialog'

const { listWebhookMatchingEvents, previewWebhook, resendWebhook, getCapabilities, resolveBlockByDate } = vi.hoisted(() => ({
  listWebhookMatchingEvents: vi.fn(),
  previewWebhook: vi.fn(),
  resendWebhook: vi.fn(),
  getCapabilities: vi.fn(),
  resolveBlockByDate: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  class WebhooksApi {
    listWebhookMatchingEvents = listWebhookMatchingEvents
    previewWebhook = previewWebhook
    resendWebhook = resendWebhook
  }
  class CapabilitiesApi {
    getCapabilities = getCapabilities
  }
  class BlocksApi {
    resolveBlockByDate = resolveBlockByDate
  }
  return { ...actual, WebhooksApi, CapabilitiesApi, BlocksApi }
})

const WEBHOOK_ID = 'wh-1'
const ID1 = '11111111-1111-1111-1111-111111111111'
const ID2 = '22222222-2222-2222-2222-222222222222'
const ID3 = '33333333-3333-3333-3333-333333333333'

function event(id: string, blockNumber: number, over: Partial<IndexedEvent> = {}): IndexedEvent {
  return {
    id,
    contractAddress: '0x0000000000000000000000000000000000000001',
    network: 'TESTNET',
    blockNumber,
    blockHash: '0xaa',
    txHash: '0xbb',
    logIndex: 0,
    decoded: true,
    eventName: 'ValueChanged',
    decodedParameters: { value: String(blockNumber) },
    blockTimestamp: '2026-02-01T00:00:00Z',
    createdAt: '2026-02-01T00:00:00Z',
    ...over,
  }
}

function page(content: IndexedEvent[], over: Record<string, unknown> = {}) {
  return { data: { content, scanned: content.length, scanTruncated: false, filtersApplied: false, warnings: [], ...over } }
}

function axiosError(status: number, code: string, message: string, headers: Record<string, string> = {}): AxiosError {
  const response = { status, statusText: '', data: { code, message, timestamp: 'now' }, headers, config: {} } as unknown as AxiosResponse
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, response)
}

const PREVIEW_OK = {
  data: {
    enrichmentBlock: { mode: 'EVENT_BLOCK', warnings: [] },
    items: [
      {
        eventId: ID1,
        matches: true,
        payload: '{"value":"1500","supply":"7"}',
        modelState: { stateAt: '2026-02-01T00:00:00Z', newerThanEvent: true },
        enrichment: {
          blockUsed: '10',
          calls: [
            {
              profileId: 'p1',
              targetContractAddress: '0x0000000000000000000000000000000000000001',
              methodName: 'totalSupply',
              status: 'OK',
              outputs: { supply: '7' },
              blockUsed: '10',
            },
          ],
          unresolvedTokens: [],
          failuresSummary: [],
        },
        warnings: ['MODEL_STATE_NEWER_THAN_EVENT'],
      },
    ],
    failuresSummary: [],
    warnings: [],
  },
}

function renderDialog(extra: { webhookActive?: boolean; onViewDeliveries?: () => void } = {}) {
  return renderWithProviders(
    <WebhookResendDialog
      webhookId={WEBHOOK_ID}
      webhookActive={extra.webhookActive ?? true}
      open
      intent="preview"
      onClose={vi.fn()}
      onViewDeliveries={extra.onViewDeliveries ?? vi.fn()}
    />,
  )
}

async function selectEvent(blockNumber: number) {
  await userEvent.click(await screen.findByLabelText(new RegExp(`selecionar evento do bloco ${blockNumber} `)))
}

beforeEach(() => {
  for (const mock of [listWebhookMatchingEvents, previewWebhook, resendWebhook, getCapabilities, resolveBlockByDate]) mock.mockReset()
  getCapabilities.mockResolvedValue({
    data: { webhookUrlOverrideEnabled: false, webhookResendMaxEventsPerRequest: 3, webhookPreviewMaxEvents: 2 },
  })
  listWebhookMatchingEvents.mockResolvedValue(page([event(ID1, 10), event(ID2, 9), event(ID3, 8)]))
})

describe('WebhookResendDialog — seletor de eventos', () => {
  it('lista os eventos com bloco, logIndex, nome, timestamp e resumo dos parâmetros', async () => {
    renderDialog()
    expect((await screen.findAllByText('ValueChanged')).length).toBe(3)
    expect(screen.getAllByText('value=10').length).toBeGreaterThan(0)
    expect(screen.getByText(/Limites efetivos: pré-visualizar até 2 evento\(s\); reenviar até 3/)).toBeInTheDocument()
  })

  it('mostra o estado vazio e o erro da listagem', async () => {
    listWebhookMatchingEvents.mockResolvedValue(page([]))
    renderDialog()
    expect(await screen.findByText(/Nenhum evento que case com este webhook/)).toBeInTheDocument()
  })

  it('alterna entre filtro por data e por bloco e envia o intervalo de blocos', async () => {
    renderDialog()
    await screen.findByLabelText(/selecionar evento do bloco 10 /)
    expect(screen.getByLabelText('De')).toBeInTheDocument()
    expect(screen.getAllByText(/Fuso:/).length).toBeGreaterThan(0)

    await userEvent.click(screen.getByRole('button', { name: 'Por bloco' }))
    await userEvent.type(screen.getByLabelText('Bloco inicial'), '5')
    await userEvent.type(screen.getByLabelText('Bloco final'), '20')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))

    await waitFor(() =>
      expect(listWebhookMatchingEvents).toHaveBeenLastCalledWith(WEBHOOK_ID, 5, 20, undefined, undefined, undefined, 20),
    )
  })

  it('filtro por data envia ISO-8601 com offset', async () => {
    renderDialog()
    await screen.findByLabelText(/selecionar evento do bloco 10 /)
    await userEvent.type(screen.getByLabelText('De'), '2026-02-01T10:30')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    await waitFor(() => {
      const call = listWebhookMatchingEvents.mock.calls.at(-1)
      expect(call?.[3]).toMatch(/^2026-02-01T10:30:00[+-]\d{2}:\d{2}$/)
    })
  })

  it('pagina por cursor com "Carregar mais" e usa nextCursor', async () => {
    listWebhookMatchingEvents
      .mockResolvedValueOnce(page([event(ID1, 10)], { nextCursor: '10:0' }))
      .mockResolvedValueOnce(page([event(ID2, 9)]))
    renderDialog()
    await screen.findByLabelText(/selecionar evento do bloco 10 /)
    await userEvent.click(screen.getByRole('button', { name: 'Carregar mais' }))
    await screen.findByLabelText(/selecionar evento do bloco 9 /)
    expect(listWebhookMatchingEvents.mock.calls.at(-1)?.[5]).toBe('10:0')
    expect(screen.queryByRole('button', { name: 'Carregar mais' })).not.toBeInTheDocument()
  })

  it('avisa scanTruncated com a contagem de eventos varridos e traduz warnings', async () => {
    listWebhookMatchingEvents.mockResolvedValue(
      page([event(ID1, 10)], {
        scanned: 5000,
        scanTruncated: true,
        nextCursor: '10:0',
        warnings: ['DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP:3', 'CODIGO_NOVO:1'],
      }),
    )
    renderDialog()
    expect(await screen.findByText(/Busca limitada a 5000 eventos varridos/)).toBeInTheDocument()
    expect(screen.getByText(/3 evento\(s\) sem timestamp de bloco/)).toBeInTheDocument()
    expect(screen.getByText('CODIGO_NOVO:1')).toBeInTheDocument()
  })

  it('seleção múltipla, "selecionar todos" e contador; avisa ao passar dos limites de capabilities', async () => {
    renderDialog()
    await selectEvent(10)
    expect(screen.getByText('1 selecionado(s)')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('selecionar todos os eventos carregados'))
    expect(screen.getByText('3 selecionado(s)')).toBeInTheDocument()
    // preview max 2 (3 > 2): aviso e botão desabilitado; resend max 3 (3 <= 3): sem aviso de reenvio.
    expect(screen.getByText(/Para pré-visualizar, selecione no máximo 2/)).toBeInTheDocument()
    expect(screen.queryByText(/Para reenviar, selecione no máximo/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Pré-visualizar' })).toBeDisabled()
  })

  it('usa defaults conservadores (5/50) quando capabilities falha', async () => {
    getCapabilities.mockRejectedValue(new Error('indisponível'))
    renderDialog()
    expect(await screen.findByText(/pré-visualizar até 5 evento\(s\); reenviar até 50/)).toBeInTheDocument()
    expect(screen.getByText(/valores padrão conservadores/)).toBeInTheDocument()
  })

  it('resolve data em bloco e preenche o intervalo, mostrando origem/aproximação/avisos', async () => {
    resolveBlockByDate.mockResolvedValue({
      data: { blockNumber: 42, source: 'COMBINED', approximate: true, warnings: ['DATE_AFTER_LATEST'] },
    })
    renderDialog()
    await screen.findByLabelText(/selecionar evento do bloco 10 /)
    await userEvent.click(screen.getByRole('button', { name: 'Por bloco' }))
    await userEvent.type(screen.getByLabelText('Data'), '2026-02-01T10:30')
    await userEvent.click(screen.getByRole('button', { name: /Resolver data/ }))
    expect(await screen.findByText(/Bloco 42/)).toBeInTheDocument()
    expect(screen.getByText(/origem: COMBINED · aproximado/)).toBeInTheDocument()
    expect(screen.getByText(/posterior ao bloco mais recente/)).toBeInTheDocument()
    expect(resolveBlockByDate.mock.calls[0][1]).toBe('FLOOR')
    await userEvent.click(screen.getByRole('button', { name: 'Usar como bloco inicial' }))
    expect(screen.getByLabelText('Bloco inicial')).toHaveValue('42')
  })
})

describe('WebhookResendDialog — pré-visualização', () => {
  it('sucesso: payload final, modelState newerThanEvent, chamada OK, e nada é enviado nem gravado', async () => {
    previewWebhook.mockResolvedValue(PREVIEW_OK)
    renderDialog()
    await selectEvent(10)
    expect(screen.getByText(/nada é enviado nem gravado/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Pré-visualizar' }))
    const results = await screen.findByLabelText('resultado da pré-visualização')
    expect(within(results).getByText('Casa com o webhook')).toBeInTheDocument()
    expect(within(results).getByText(/"supply": "7"/)).toBeInTheDocument()
    expect(within(results).getByText(/estado ATUAL do modelo, que avançou/)).toBeInTheDocument()
    expect(within(results).getByText('totalSupply')).toBeInTheDocument()
    expect(within(results).getByText('OK')).toBeInTheDocument()
    expect(previewWebhook).toHaveBeenCalledWith(WEBHOOK_ID, {
      eventIds: [ID1],
      enrichmentBlock: { mode: 'EVENT_BLOCK' },
      diagnose: false,
    })
  })

  it('evento que não casa é renderizado com chip e aviso', async () => {
    previewWebhook.mockResolvedValue({
      data: {
        items: [{ eventId: ID2, matches: false, notMatchingReason: 'FILTER_NOT_MET', payload: '{"a":1}', warnings: [] }],
        warnings: [],
      },
    })
    renderDialog()
    await selectEvent(9)
    await userEvent.click(screen.getByRole('button', { name: 'Pré-visualizar' }))
    expect(await screen.findByText(/Não casa: filtros do webhook não atendidos/)).toBeInTheDocument()
    expect(screen.getByText(/NÃO casaria com o webhook/)).toBeInTheDocument()
    expect(screen.getByText(/"a": 1/)).toBeInTheDocument()
  })

  it('payload ausente e item EVENT_NOT_FOUND não quebram a tela', async () => {
    previewWebhook.mockResolvedValue({
      data: { items: [{ eventId: ID1, matches: false, notMatchingReason: 'EVENT_NOT_FOUND' }], warnings: [] },
    })
    renderDialog()
    await selectEvent(10)
    await userEvent.click(screen.getByRole('button', { name: 'Pré-visualizar' }))
    expect(await screen.findByText(/Payload indisponível/)).toBeInTheDocument()
    expect(screen.getByText(/evento não encontrado/)).toBeInTheDocument()
  })

  it('falha de enriquecimento por chamada mostra code, message bruta, rpcCode, classificação, tokens e diagnóstico como INDÍCIO', async () => {
    const message = 'eth_call em 0x…01.totalSupply (bloco 10) não retornou resultado (code=-32603): Internal error'
    previewWebhook.mockResolvedValue({
      data: {
        enrichmentBlock: { mode: 'EVENT_BLOCK', warnings: [] },
        items: [
          {
            eventId: ID1,
            matches: true,
            payload: '{"supply":null}',
            enrichment: {
              blockUsed: '10',
              calls: [
                {
                  profileId: 'p1',
                  targetContractAddress: '0x0000000000000000000000000000000000000001',
                  methodName: 'totalSupply',
                  status: 'ERROR',
                  code: 'RPC_ERROR',
                  message,
                  rpcCode: -32603,
                  rpcData: 'dados-brutos',
                  classification: 'NODE_ERROR',
                  blockUsed: '10',
                },
              ],
              unresolvedTokens: ['enriched.supply'],
              failuresSummary: [
                { contract: '0x0000000000000000000000000000000000000001', method: 'totalSupply', block: '10', count: 1, code: 'RPC_ERROR', rpcCode: -32603 },
              ],
              diagnoses: [
                {
                  targetContractAddress: '0x0000000000000000000000000000000000000001',
                  methodName: 'totalSupply',
                  verdict: 'OK_AT_LATEST_ONLY',
                  atLatest: { block: 'latest', ok: true },
                  probe: 'PROBE_STATE_OK',
                },
              ],
            },
            warnings: [],
          },
        ],
        failuresSummary: [],
        warnings: [],
      },
    })
    renderDialog()
    await selectEvent(10)
    await userEvent.click(screen.getByLabelText(/Diagnosticar falhas/))
    await userEvent.click(screen.getByRole('button', { name: 'Pré-visualizar' }))
    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.getByText('code: RPC_ERROR')).toBeInTheDocument()
    expect(screen.getByText('rpcCode: -32603')).toBeInTheDocument()
    expect(screen.getByText('classificação: NODE_ERROR')).toBeInTheDocument()
    expect(screen.getByText('rpcData: dados-brutos')).toBeInTheDocument()
    expect(screen.getByText('enriched.supply')).toBeInTheDocument()
    expect(screen.getByText(/Falhas de enriquecimento \(agregadas\)/)).toBeInTheDocument()
    expect(screen.getByText(/Diagnóstico \(INDÍCIO, não é causa comprovada\)/)).toBeInTheDocument()
    expect(screen.getByText('Indício: OK_AT_LATEST_ONLY')).toBeInTheDocument()
    expect(previewWebhook.mock.calls[0][1].diagnose).toBe(true)
  })

  it('status e veredito desconhecidos aparecem crus', async () => {
    previewWebhook.mockResolvedValue({
      data: {
        items: [
          {
            eventId: ID1,
            matches: true,
            payload: '{}',
            enrichment: {
              calls: [{ profileId: 'p', targetContractAddress: '0xabc', methodName: 'm', status: 'STATUS_NOVO' }],
              unresolvedTokens: [],
              diagnoses: [{ targetContractAddress: '0xabc', methodName: 'm', verdict: 'VEREDITO_NOVO' }],
            },
          },
        ],
      },
    })
    renderDialog()
    await selectEvent(10)
    await userEvent.click(screen.getByRole('button', { name: 'Pré-visualizar' }))
    expect(await screen.findByText('STATUS_NOVO')).toBeInTheDocument()
    expect(screen.getAllByText(/VEREDITO_NOVO/).length).toBeGreaterThan(0)
  })

  it('exibe o bloco resolvido de BLOCK_AT_DATE (origem/aproximação)', async () => {
    previewWebhook.mockResolvedValue({
      data: {
        enrichmentBlock: { mode: 'BLOCK_AT_DATE', blockNumber: 40, resolvedFromDate: '2026-02-01T10:00:00Z', source: 'RPC', approximate: true, warnings: ['RPC_BUDGET_EXHAUSTED'] },
        items: [],
      },
    })
    renderDialog()
    await selectEvent(10)
    await userEvent.click(screen.getByRole('combobox', { name: 'Bloco do enriquecimento' }))
    await userEvent.click(screen.getByRole('option', { name: 'Bloco de uma data' }))
    await userEvent.type(screen.getByLabelText('Data/hora do bloco'), '2026-02-01T10:00')
    await userEvent.click(screen.getByRole('button', { name: 'Pré-visualizar' }))
    expect(await screen.findByText(/bloco usado: 40/)).toBeInTheDocument()
    expect(screen.getByText(/origem: RPC/)).toBeInTheDocument()
    expect(screen.getByText(/resolução aproximada/)).toBeInTheDocument()
    expect(screen.getByText(/limite de consultas ao nó/)).toBeInTheDocument()
    expect(previewWebhook.mock.calls[0][1].enrichmentBlock).toMatchObject({ mode: 'BLOCK_AT_DATE', blockDate: expect.stringMatching(/^2026-02-01T10:00:00[+-]\d{2}:\d{2}$/) })
  })

  it('erro do preview aparece via ApiErrorAlert', async () => {
    previewWebhook.mockRejectedValue(axiosError(400, 'TOO_MANY_EVENTS', 'Muitos eventos'))
    renderDialog()
    await selectEvent(10)
    await userEvent.click(screen.getByRole('button', { name: 'Pré-visualizar' }))
    expect(await screen.findByText('Muitos eventos')).toBeInTheDocument()
  })
})

describe('WebhookResendDialog — reenvio', () => {
  async function openConfirm() {
    await selectEvent(10)
    await userEvent.click(screen.getByRole('button', { name: 'Reenviar...' }))
    return screen.findByRole('dialog', { name: 'Confirmar reenvio' })
  }

  it('confirmação explícita com resumo e avisos; sem URL de teste quando a capacidade está desligada', async () => {
    renderDialog()
    const confirm = await openConfirm()
    expect(within(confirm).getByText(/1 evento\(s\) serão enfileirados/)).toBeInTheDocument()
    expect(within(confirm).getByText(/estado ATUAL do modelo/)).toBeInTheDocument()
    expect(within(confirm).getByText(/NÃO altera os eventos/)).toBeInTheDocument()
    expect(within(confirm).queryByLabelText(/URL de teste/)).not.toBeInTheDocument()
    expect(resendWebhook).not.toHaveBeenCalled()
  })

  it('URL de teste só aparece com webhookUrlOverrideEnabled=true e vai em overrideUrl', async () => {
    getCapabilities.mockResolvedValue({
      data: { webhookUrlOverrideEnabled: true, webhookResendMaxEventsPerRequest: 3, webhookPreviewMaxEvents: 2 },
    })
    resendWebhook.mockResolvedValue({ data: { accepted: [], skipped: [] } })
    renderDialog()
    await screen.findByText(/reenviar até 3/)
    await waitFor(() => expect(getCapabilities).toHaveBeenCalled())
    const confirm = await openConfirm()
    await userEvent.click(await within(confirm).findByLabelText('Enviar para uma URL de teste'))
    expect(within(confirm).getByText(/Não altera o webhook cadastrado/)).toBeInTheDocument()
    await userEvent.type(within(confirm).getByLabelText('URL de teste'), 'https://example.invalid/hook')
    await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar reenvio' }))
    await waitFor(() => expect(resendWebhook).toHaveBeenCalled())
    expect(resendWebhook.mock.calls[0][1]).toMatchObject({ eventIds: [ID1], overrideUrl: 'https://example.invalid/hook' })
  })

  it('mostra accepted (com atalho ao histórico), skipped traduzido, modelState e warnings; depois fecha a confirmação', async () => {
    resendWebhook.mockResolvedValue({
      data: {
        accepted: [{ eventId: ID1, deliveryId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }],
        skipped: [
          { eventId: ID2, reason: 'NOT_MATCHING' },
          { eventId: ID3, reason: 'MOTIVO_NOVO' },
        ],
        modelState: { stateAt: '2026-10-01T12:00:00Z', newerThanEvent: true },
        enrichmentBlock: { mode: 'EVENT_BLOCK', warnings: [] },
        warnings: ['MODEL_STATE_NEWER_THAN_EVENT', 'RANGE_TRUNCATED'],
      },
    })
    const onViewDeliveries = vi.fn()
    const { queryClient } = renderDialog({ onViewDeliveries })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const confirm = await openConfirm()
    await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar reenvio' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Confirmar reenvio' })).not.toBeInTheDocument())
    const result = await screen.findByLabelText('resultado do reenvio')
    expect(within(result).getByText(/1 evento\(s\) enfileirado\(s\) para reenvio; 2 ignorado\(s\)/)).toBeInTheDocument()
    expect(within(result).getByText(/não casa com o webhook/)).toBeInTheDocument()
    expect(within(result).getByText(/MOTIVO_NOVO/)).toBeInTheDocument()
    expect(within(result).getByText(/estado ATUAL do modelo/)).toBeInTheDocument()
    expect(within(result).getByText(/limite por reenvio/)).toBeInTheDocument()
    await userEvent.click(within(result).getByRole('button', { name: 'Ver histórico de entregas' }))
    expect(onViewDeliveries).toHaveBeenCalled()
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['webhook-deliveries', WEBHOOK_ID] })
  })

  it('409 WEBHOOK_INACTIVE mostra a mensagem do backend e a dica de ativar o webhook ou usar URL de teste', async () => {
    resendWebhook.mockRejectedValue(axiosError(409, 'WEBHOOK_INACTIVE', 'Webhook inativo'))
    renderDialog({ webhookActive: false })
    const confirm = await openConfirm()
    expect(within(confirm).getByText(/só é aceito com uma URL de teste válida/)).toBeInTheDocument()
    await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar reenvio' }))
    expect(await within(confirm).findByText('Webhook inativo')).toBeInTheDocument()
    expect(within(confirm).getByText('Ative o webhook ou informe uma URL de teste válida.')).toBeInTheDocument()
  })

  it('429 com Retry-After desabilita o botão e mostra "tente novamente em N s"', async () => {
    resendWebhook.mockRejectedValue(
      axiosError(429, 'RESEND_QUEUE_FULL', 'Fila de reenvios cheia (limite 500); tente novamente em 30 segundo(s)', { 'retry-after': '30' }),
    )
    renderDialog()
    const confirm = await openConfirm()
    await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar reenvio' }))
    expect(await within(confirm).findByText(/Tente novamente em 30 s/)).toBeInTheDocument()
    expect(within(confirm).getByRole('button', { name: 'Confirmar reenvio' })).toBeDisabled()
  })

  it('400 TOO_MANY_EVENTS mostra a mensagem do backend', async () => {
    resendWebhook.mockRejectedValue(axiosError(400, 'TOO_MANY_EVENTS', 'No máximo 50 eventos por reenvio.'))
    renderDialog()
    const confirm = await openConfirm()
    await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar reenvio' }))
    expect(await within(confirm).findByText('No máximo 50 eventos por reenvio.')).toBeInTheDocument()
    expect(within(confirm).getByRole('button', { name: 'Confirmar reenvio' })).toBeEnabled()
  })

  it('reenvia o intervalo do filtro (range) quando escolhido', async () => {
    resendWebhook.mockResolvedValue({ data: { accepted: [], skipped: [], warnings: ['RANGE_TRUNCATED'] } })
    renderDialog()
    await screen.findByLabelText(/selecionar evento do bloco 10 /)
    await userEvent.click(screen.getByRole('button', { name: 'Por bloco' }))
    await userEvent.type(screen.getByLabelText('Bloco inicial'), '5')
    await userEvent.click(screen.getByRole('button', { name: 'Buscar' }))
    await selectEvent(10)
    await userEvent.click(screen.getByRole('button', { name: 'Reenviar...' }))
    const confirm = await screen.findByRole('dialog', { name: 'Confirmar reenvio' })
    await userEvent.click(within(confirm).getByLabelText(/Todos os eventos que casam no intervalo do filtro/))
    await userEvent.click(within(confirm).getByRole('button', { name: 'Confirmar reenvio' }))
    await waitFor(() => expect(resendWebhook).toHaveBeenCalled())
    expect(resendWebhook.mock.calls[0][1]).toMatchObject({ range: { fromBlock: 5 } })
    expect(resendWebhook.mock.calls[0][1].eventIds).toBeUndefined()
  })
})
