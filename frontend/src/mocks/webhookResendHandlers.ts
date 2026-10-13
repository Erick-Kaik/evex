/**
 * Handlers do MSW da pré-visualização/reenvio de webhooks (E33, DEC-113), coerentes com o contrato:
 * `matching-events`, `preview`, `resend`, `blocks/by-date` e `capabilities`. Dados 100% fictícios.
 *
 * Cenários:
 * - eventos do ERC20 de exemplo: 60 sintéticos (cursor `bloco:logIndex`; 2ª página com `scanTruncated`;
 *   timestamps ausentes a cada 20 → aviso no filtro por data) + os decodificados do seed;
 * - preview por `eventId`: `1111…` sucesso com `newerThanEvent`; `2222…` não casa; `3333…` falha de
 *   enriquecimento (-32603; com `diagnose` → `OK_AT_LATEST_ONLY`); `4444…` não encontrado;
 * - resend: webhook pausado → 409; `overrideUrl` com a capacidade desligada → 400; `> limite` → 400;
 *   o 3º reenvio da sessão → 429 com `Retry-After: 30`;
 * - capacidades: `webhookUrlOverrideEnabled` false; ligue com `?urlOverride=1` na URL do front.
 *
 * Não faz parte do produto.
 */
import { http, HttpResponse } from 'msw'
import type {
  EnrichmentCallResult,
  Event as IndexedEvent,
  WebhookConfig,
  WebhookDelivery,
  WebhookPreviewItem,
  WebhookPreviewPayload,
  WebhookResendPayload,
} from '../api/api'
import { ADDR, db, helpers } from './db'

const PREVIEW_MAX = 5
const RESEND_MAX = 50
const OK_ID = '11111111-1111-1111-1111-111111111111'
const NOT_MATCHING_ID = '22222222-2222-2222-2222-222222222222'
const FAILING_ID = '33333333-3333-3333-3333-333333333333'
const PROFILE_ID = '22222222-2222-2222-2222-222222222222'
const CONTRACT = '0x0000000000000000000000000000000000000001'

const apiError = (code: string, message: string, status: number, headers?: Record<string, string>) =>
  HttpResponse.json({ code, message, timestamp: new Date().toISOString() }, { status, headers })

/** 60 eventos `Transfer` sintéticos do ERC20 de exemplo, do mais recente (bloco 300) ao mais antigo. */
const synthetic: IndexedEvent[] = Array.from({ length: 60 }, (_, index) => {
  const blockNumber = 300 - index
  const special = [OK_ID, NOT_MATCHING_ID, FAILING_ID][index]
  return {
    id: special ?? `aaaaaaaa-0000-0000-0000-${String(index).padStart(12, '0')}`,
    contractAddress: ADDR.erc20,
    network: 'TESTNET',
    blockNumber,
    blockHash: '0x' + 'ab'.repeat(32),
    txHash: '0x' + blockNumber.toString(16).padStart(6, '0') + 'cdef'.repeat(14),
    logIndex: index % 2,
    decoded: true,
    eventName: 'Transfer',
    decodedParameters: { from: helpers.WALLET.zero, to: helpers.WALLET.alice, value: String(1000 + blockNumber) },
    // Sem timestamp a cada 20 eventos (só eventos ingeridos têm timestamp — DEC-115).
    blockTimestamp: index % 20 === 19 ? undefined : new Date(Date.UTC(2026, 1, 1) + blockNumber * 3_600_000).toISOString(),
    createdAt: helpers.NOW,
  }
})

function candidates(webhook: WebhookConfig): IndexedEvent[] {
  const own = db.events.filter(
    (e) => e.decoded && e.contractAddress.toLowerCase() === webhook.contractAddress.toLowerCase() && e.eventName === webhook.eventName,
  )
  const extra = webhook.contractAddress.toLowerCase() === ADDR.erc20 && webhook.eventName === 'Transfer' ? synthetic : []
  return [...extra, ...own].sort((a, b) => b.blockNumber - a.blockNumber || b.logIndex - a.logIndex)
}

function findEvent(id: string): IndexedEvent | undefined {
  return synthetic.find((e) => e.id === id) ?? db.events.find((e) => e.id === id)
}

const cursorOf = (e: IndexedEvent) => `${e.blockNumber}:${e.logIndex}`

function parseCursor(cursor: string | null): { block: number; log: number } | null {
  if (!cursor) return null
  const [block, log] = cursor.split(':').map(Number)
  return Number.isInteger(block) && Number.isInteger(log) ? { block, log } : null
}

function renderPayload(event: IndexedEvent, enriched: string): string {
  return JSON.stringify({ value: event.decodedParameters?.value ?? null, supply: enriched === 'null' ? null : enriched })
}

function enrichmentFor(event: IndexedEvent, blockUsed: string, diagnose: boolean) {
  if (event.id === FAILING_ID) {
    const call: EnrichmentCallResult = {
      profileId: PROFILE_ID,
      targetContractAddress: CONTRACT,
      methodName: 'totalSupply',
      status: 'ERROR',
      code: 'RPC_ERROR',
      message: `eth_call em 0x…01.totalSupply (bloco ${blockUsed}) não retornou resultado (code=-32603): Internal error`,
      rpcCode: -32603,
      classification: 'NODE_ERROR',
      blockUsed,
    }
    return {
      payload: renderPayload(event, 'null'),
      enrichment: {
        blockUsed,
        calls: [call],
        unresolvedTokens: ['enriched.supply'],
        failuresSummary: [
          { contract: CONTRACT, method: 'totalSupply', block: blockUsed, count: 1, code: 'RPC_ERROR', rpcCode: -32603, message: call.message },
        ],
        ...(diagnose
          ? {
              diagnoses: [
                {
                  profileId: PROFILE_ID,
                  targetContractAddress: CONTRACT,
                  methodName: 'totalSupply',
                  verdict: 'OK_AT_LATEST_ONLY',
                  atLatest: { block: 'latest', ok: true },
                  atEventBlock: { block: blockUsed, ok: false, code: 'RPC_ERROR', rpcCode: -32603 },
                  probe: 'PROBE_STATE_OK',
                },
              ],
            }
          : {}),
      },
    }
  }
  return {
    payload: renderPayload(event, '7'),
    enrichment: {
      blockUsed,
      calls: [
        { profileId: PROFILE_ID, targetContractAddress: CONTRACT, methodName: 'totalSupply', status: 'OK', outputs: { supply: '7' }, blockUsed },
      ],
      unresolvedTokens: [],
      failuresSummary: [],
    },
  }
}

function resolvedBlock(spec: WebhookPreviewPayload['enrichmentBlock']) {
  const mode = spec?.mode ?? 'EVENT_BLOCK'
  if (mode === 'BLOCK_AT_DATE') {
    return { mode, blockNumber: 240, resolvedFromDate: spec?.blockDate, source: 'COMBINED', approximate: true, warnings: [] as string[] }
  }
  if (mode === 'BLOCK_NUMBER') return { mode, blockNumber: spec?.blockNumber, warnings: [] as string[] }
  return { mode, warnings: [] as string[] }
}

let resendCalls = 0

export const webhookResendHandlers = [
  http.get('*/v1/capabilities', async () =>
    HttpResponse.json({
      webhookUrlOverrideEnabled: new URLSearchParams(window.location.search).get('urlOverride') === '1',
      webhookResendMaxEventsPerRequest: RESEND_MAX,
      webhookPreviewMaxEvents: PREVIEW_MAX,
      webhookResendMaxPendingResends: 500,
      webhookResendMaxPerMinutePerWebhook: 10,
      webhookMaxRequestsPerMinutePerInstance: 30,
    }),
  ),

  http.get('*/v1/blocks/by-date', async ({ request }) => {
    const url = new URL(request.url)
    const date = url.searchParams.get('date')
    const direction = url.searchParams.get('direction') ?? 'FLOOR'
    if (!date || Number.isNaN(Date.parse(date))) return apiError('VALIDATION_ERROR', 'Data inválida.', 400)
    if (direction !== 'FLOOR' && direction !== 'CEILING') return apiError('INVALID_DIRECTION', 'Direção inválida.', 400)
    const hours = Math.floor((Date.parse(date) - Date.UTC(2026, 1, 1)) / 3_600_000)
    if (hours < 0) {
      return HttpResponse.json({ source: 'INGESTED', approximate: true, warnings: ['DATE_BEFORE_GENESIS'] })
    }
    const blockNumber = direction === 'FLOOR' ? hours : hours + 1
    return HttpResponse.json({
      blockNumber,
      blockTimestamp: new Date(Date.UTC(2026, 1, 1) + blockNumber * 3_600_000).toISOString(),
      source: 'COMBINED',
      approximate: true,
      warnings: [],
    })
  }),

  http.get('*/v1/webhooks/:id/matching-events', async ({ params, request }) => {
    const webhook = db.webhooks.find((w) => w.id === params.id)
    if (!webhook) return apiError('WEBHOOK_CONFIG_NOT_FOUND', 'Webhook não encontrado.', 404)
    const url = new URL(request.url)
    const num = (key: string) => (url.searchParams.get(key) ? Number(url.searchParams.get(key)) : undefined)
    const fromBlock = num('fromBlock')
    const toBlock = num('toBlock')
    const fromDate = url.searchParams.get('fromDate')
    const toDate = url.searchParams.get('toDate')
    const size = Math.min(100, num('size') ?? 20)
    const cursor = parseCursor(url.searchParams.get('cursor'))
    if (url.searchParams.get('cursor') && !cursor) return apiError('INVALID_CURSOR', 'Cursor inválido.', 400)

    let list = candidates(webhook)
    if (fromBlock !== undefined) list = list.filter((e) => e.blockNumber >= fromBlock)
    if (toBlock !== undefined) list = list.filter((e) => e.blockNumber <= toBlock)
    let withoutTimestamp = 0
    if (fromDate || toDate) {
      withoutTimestamp = list.filter((e) => !e.blockTimestamp).length
      list = list.filter(
        (e) =>
          e.blockTimestamp &&
          (!fromDate || Date.parse(e.blockTimestamp) >= Date.parse(fromDate)) &&
          (!toDate || Date.parse(e.blockTimestamp) <= Date.parse(toDate)),
      )
    }
    if (cursor) list = list.filter((e) => e.blockNumber < cursor.block || (e.blockNumber === cursor.block && e.logIndex < cursor.log))

    const content = list.slice(0, size)
    const hasMore = list.length > size
    // Demonstra `scanTruncated`: quando a página começa no meio da lista sintética (cursor presente e
    // com mais itens depois), finge que a varredura bateu no teto sem completar `size`.
    const truncated = Boolean(cursor) && hasMore && cursor !== null && cursor.block <= 281
    const page = truncated ? content.slice(0, Math.ceil(size / 2)) : content
    return HttpResponse.json({
      content: page,
      ...(hasMore && page.length > 0 ? { nextCursor: cursorOf(page[page.length - 1]) } : {}),
      scanned: truncated ? 5000 : page.length,
      scanTruncated: truncated,
      filtersApplied: webhook.filters.length > 0,
      warnings: withoutTimestamp > 0 ? [`DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP:${withoutTimestamp}`] : [],
    })
  }),

  http.post('*/v1/webhooks/:id/preview', async ({ params, request }) => {
    const webhook = db.webhooks.find((w) => w.id === params.id)
    if (!webhook) return apiError('WEBHOOK_CONFIG_NOT_FOUND', 'Webhook não encontrado.', 404)
    const body = (await request.json()) as WebhookPreviewPayload
    if (!body.eventIds || body.eventIds.length === 0 || body.eventIds.length > PREVIEW_MAX) {
      return apiError('TOO_MANY_EVENTS', `Informe de 1 a ${PREVIEW_MAX} eventos para a pré-visualização.`, 400)
    }
    const enrichmentBlock = resolvedBlock(body.enrichmentBlock)
    const items: WebhookPreviewItem[] = body.eventIds.map((eventId) => {
      const event = findEvent(eventId)
      if (!event) return { eventId, matches: false, notMatchingReason: 'EVENT_NOT_FOUND', warnings: [] }
      const blockUsed = enrichmentBlock.blockNumber ? String(enrichmentBlock.blockNumber) : body.enrichmentBlock?.mode === 'LATEST' ? 'latest' : String(event.blockNumber)
      const { payload, enrichment } = enrichmentFor(event, blockUsed, body.diagnose === true)
      const newer = event.id !== FAILING_ID
      return {
        eventId,
        matches: event.id !== NOT_MATCHING_ID,
        ...(event.id === NOT_MATCHING_ID ? { notMatchingReason: 'FILTER_NOT_MET' } : {}),
        payload,
        modelState: { stateAt: '2026-10-01T00:00:00Z', newerThanEvent: newer },
        enrichment,
        warnings: newer ? ['MODEL_STATE_NEWER_THAN_EVENT'] : [],
      }
    })
    const failuresSummary = items.flatMap((item) => item.enrichment?.failuresSummary ?? [])
    return HttpResponse.json({ enrichmentBlock, items, failuresSummary, warnings: [] })
  }),

  http.post('*/v1/webhooks/:id/resend', async ({ params, request }) => {
    const webhook = db.webhooks.find((w) => w.id === params.id)
    if (!webhook) return apiError('WEBHOOK_CONFIG_NOT_FOUND', 'Webhook não encontrado.', 404)
    const body = (await request.json()) as WebhookResendPayload
    resendCalls += 1
    if (resendCalls % 3 === 0) {
      return apiError('RESEND_QUEUE_FULL', 'Fila de reenvios cheia (limite 500); tente novamente em 30 segundo(s)', 429, { 'Retry-After': '30' })
    }
    const overrideEnabled = new URLSearchParams(window.location.search).get('urlOverride') === '1'
    if (body.overrideUrl && !overrideEnabled) {
      return apiError('URL_OVERRIDE_DISABLED', 'A URL de teste está desabilitada neste servidor.', 400)
    }
    if (!webhook.active && !body.overrideUrl) {
      return apiError('WEBHOOK_INACTIVE', 'Webhook inativo: ative-o ou informe uma URL de teste.', 409)
    }
    const warnings: string[] = []
    let ids = body.eventIds ?? []
    if (body.range) {
      const { fromBlock, toBlock } = body.range
      ids = candidates(webhook)
        .filter((e) => (fromBlock === undefined || e.blockNumber >= fromBlock) && (toBlock === undefined || e.blockNumber <= toBlock))
        .map((e) => e.id)
      if (ids.length > RESEND_MAX) {
        ids = ids.slice(0, RESEND_MAX)
        warnings.push('RANGE_TRUNCATED')
      }
    } else if (ids.length > RESEND_MAX) {
      return apiError('TOO_MANY_EVENTS', `No máximo ${RESEND_MAX} eventos por reenvio.`, 400)
    }
    const accepted: { eventId: string; deliveryId: string }[] = []
    const skipped: { eventId: string; reason: string }[] = []
    for (const eventId of ids) {
      const event = findEvent(eventId)
      if (!event) skipped.push({ eventId, reason: 'EVENT_NOT_FOUND' })
      else if (event.id === NOT_MATCHING_ID) skipped.push({ eventId, reason: 'NOT_MATCHING' })
      else if (db.deliveries.some((d) => d.webhookConfigId === webhook.id && d.blockchainEventId === eventId && d.status === 'PENDING')) {
        skipped.push({ eventId, reason: 'ALREADY_PENDING' })
      } else {
        const delivery: WebhookDelivery = {
          id: helpers.uid(),
          webhookConfigId: webhook.id,
          blockchainEventId: eventId,
          payload: renderPayload(event, '7'),
          status: 'PENDING',
          attemptCount: 0,
          createdAt: new Date().toISOString(),
          attempts: [],
          origin: 'RESEND',
          resend: true,
          enrichmentBlockMode: body.enrichmentBlock?.mode ?? 'EVENT_BLOCK',
          modelStateAt: '2026-10-01T12:00:00Z',
          ...(body.overrideUrl ? { targetUrlOverride: new URL(body.overrideUrl).origin } : {}),
        }
        db.deliveries.unshift(delivery)
        accepted.push({ eventId, deliveryId: delivery.id })
      }
    }
    return HttpResponse.json(
      {
        accepted,
        skipped,
        modelState: { stateAt: '2026-10-01T12:00:00Z', newerThanEvent: true },
        enrichmentBlock: resolvedBlock(body.enrichmentBlock),
        warnings: ['MODEL_STATE_NEWER_THAN_EVENT', ...warnings],
      },
      { status: 202 },
    )
  }),
]
