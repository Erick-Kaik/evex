/**
 * Handlers do MSW pro modo mock (`npm run dev:mock`). Cada rota da API do Evex responde a partir do
 * `db` em memória (`./db.ts`) — mutações (`POST`/`PATCH`) alteram o `db`, então a UI parece viva.
 * Também mocka o `control-panel` (aba "Demonstração").
 *
 * Não faz parte do produto.
 */
import { http, HttpResponse, delay } from 'msw'
import type {
  AbiHistoryImportPayload,
  AbiHistoryItem,
  AbiHistoryPlan,
  BalanceMonitorConfig,
  Contract,
  ContractAbi,
  CreateBalanceMonitorPayload,
  CreateContractPayload,
  CreateWalletPayload,
  CreateWebhookPayload,
  Event as IndexedEvent,
  RegisterAbiPayload,
  UpdateBalanceMonitorStatusPayload,
  UpdateContractStatusPayload,
  UpdateWalletPayload,
  UpdateWalletStatusPayload,
  UpdateWebhookStatusPayload,
  Wallet,
  WebhookConfig,
} from '../api/api'
import {
  ADDR,
  blockscoutVerified,
  db,
  eventCatalogs,
  helpers,
  interfaceSuggestions,
} from './db'

/** Contratos cujo histórico já foi "importado" nesta sessão do mock (vira SKIP_ALREADY_IMPORTED). */
const historyImported = new Set<string>()

const IMPL = {
  v1: '0x4444000000000000000000000000000000000001',
  v2: '0x4444000000000000000000000000000000000002',
  v3: '0x4444000000000000000000000000000000000003',
}
const TX = (n: number) => `0x${n.toString(16).padStart(64, '0')}`

/**
 * Plano de histórico de ABI (DEC-105), fictício. AUTO/INGESTED_ONLY: 3 upgrades (o 3º com a mesma ABI
 * do 2º; diff do 1→2 com 3 métodos adicionados). BLOCKSCOUT_ONLY: variante com o 2º upgrade sem ABI
 * verificada (PENDING_NO_VERIFIED_ABI) e o 3º BLOCKED_AFTER_PENDING.
 */
function buildHistoryPlan(
  address: string,
  source: string,
  applied: boolean,
  replaceLegacy = false,
): AbiHistoryPlan {
  const pendingVariant = source === 'BLOCKSCOUT_ONLY'
  // Contrato com versão legada (BLOCKSCOUT sem implementação, from=0): sem a flag o legado ocupa o
  // início da linha do tempo (CONFLICT_MANUAL + BLOCKED_AFTER_PENDING); com a flag, tudo vira APPLY.
  const legacy =
    address === ADDR.legacyProxy
      ? db.abis.filter((a) => a.contractAddress === address && a.source === 'BLOCKSCOUT' && !a.implementationAddress)
      : []
  const blockedByLegacy = legacy.length > 0 && !replaceLegacy
  const done = (status: string) => (applied && status === 'APPLY' ? 'SKIP_ALREADY_IMPORTED' : status)
  const items: AbiHistoryItem[] = [
    {
      sequence: 1, validFromBlock: 0, validToBlock: 299, upgradeBlockNumber: 100, implementationAddress: IMPL.v1,
      txHash: TX(1), upgradeSource: 'BOTH', abiAvailable: true, identicalToPrevious: false,
      ...(blockedByLegacy
        ? { status: 'CONFLICT_MANUAL', statusReason: 'Já existe uma versão de ABI (legada) neste início de faixa.' }
        : { status: done('APPLY') }),
    },
    pendingVariant
      ? {
          sequence: 2, validFromBlock: 300, validToBlock: 499, upgradeBlockNumber: 300, implementationAddress: IMPL.v2,
          txHash: TX(2), upgradeSource: 'BLOCKSCOUT', abiAvailable: false, status: 'PENDING_NO_VERIFIED_ABI',
          statusReason: 'A implementação não tem ABI verificada no Blockscout.', identicalToPrevious: false,
        }
      : {
          sequence: 2, validFromBlock: 300, validToBlock: 499, upgradeBlockNumber: 300, implementationAddress: IMPL.v2,
          txHash: TX(2), upgradeSource: 'BLOCKSCOUT', abiAvailable: true, identicalToPrevious: false,
          ...(blockedByLegacy
            ? { status: 'BLOCKED_AFTER_PENDING', statusReason: 'Vem depois de um item em conflito.' }
            : { status: done('APPLY') }),
          diffFromPrevious: {
            eventsAdded: ['EventTwo(uint256)'],
            eventsRemoved: ['EventOne(uint256)'],
            methodsAdded: ['burn(uint256)', 'mint(address,uint256)', 'pause()'],
            methodsRemoved: [],
          },
        },
    pendingVariant
      ? {
          sequence: 3, validFromBlock: 500, upgradeBlockNumber: 500, implementationAddress: IMPL.v3, txHash: TX(3),
          upgradeSource: 'BLOCKSCOUT', abiAvailable: true, status: 'BLOCKED_AFTER_PENDING',
          statusReason: 'Vem depois de um item pendente.', identicalToPrevious: false,
        }
      : {
          sequence: 3, validFromBlock: 500, upgradeBlockNumber: 500, implementationAddress: IMPL.v3, txHash: TX(3),
          upgradeSource: 'INGESTED', abiAvailable: true, identicalToPrevious: true,
          ...(blockedByLegacy
            ? { status: 'BLOCKED_AFTER_PENDING', statusReason: 'Vem depois de um item em conflito.' }
            : { status: done('APPLY') }),
          diffFromPrevious: { eventsAdded: [], eventsRemoved: [], methodsAdded: [], methodsRemoved: [] },
        },
  ]
  const willApply = items.some((i) => i.status === 'APPLY')
  return {
    contractAddress: address,
    historySource: source as AbiHistoryPlan['historySource'],
    ingestedUntilBlock: 620,
    truncated: false,
    // A flag entra no fingerprint (o plano com a flag é outro plano); legacyVersionsToReplace, não.
    fingerprint: `mock-${source}-${applied ? 'applied' : 'fresh'}${replaceLegacy ? '-replace' : ''}`,
    warnings: pendingVariant ? ['Dado do Blockscout isolado: o histórico pode estar incompleto.'] : [],
    items,
    legacyVersionsToReplace:
      replaceLegacy && willApply ? legacy.map((a) => ({ abiId: a.id, validFromBlock: a.validFromBlock })) : [],
  }
}

const CONTROL_PANEL = 'http://localhost:8899'

/** Página no formato do backend (`{ page, size, totalElements, totalPages, content }`). */
function page<T>(items: T[], url: URL) {
  const p = Number(url.searchParams.get('page') ?? 0)
  const s = Number(url.searchParams.get('size') ?? 20)
  const start = p * s
  const content = items.slice(start, start + s)
  return { page: p, size: s, totalElements: items.length, totalPages: Math.max(1, Math.ceil(items.length / s)), content }
}

const lower = (v: string | null) => (v ?? '').toLowerCase()
const findContract = (address: string) => db.contracts.find((c) => c.address.toLowerCase() === address.toLowerCase())
const findWallet = (address: string) => db.wallets.find((w) => w.address.toLowerCase() === address.toLowerCase())
const notFound = (code = 'NOT_FOUND', message = 'Não encontrado') =>
  HttpResponse.json({ code, message, timestamp: new Date().toISOString() }, { status: 404 })

export const handlers = [
  // ---------------- Contratos ----------------

  http.get('*/v1/contracts', async ({ request }) => {
    await delay(120)
    const url = new URL(request.url)
    const network = url.searchParams.get('network')
    let list = db.contracts
    if (network) list = list.filter((c) => c.network === network)
    return HttpResponse.json(page(list, url))
  }),

  http.post('*/v1/contracts', async ({ request }) => {
    const body = (await request.json()) as CreateContractPayload
    if (findContract(body.address)) {
      return HttpResponse.json({ code: 'CONTRACT_ALREADY_EXISTS', message: 'Contrato já cadastrado', timestamp: new Date().toISOString() }, { status: 409 })
    }
    const created: Contract = {
      id: helpers.uid(),
      address: body.address.toLowerCase(),
      name: body.name,
      description: body.description,
      version: body.version,
      network: body.network,
      status: 'ACTIVE',
    }
    db.contracts.unshift(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.get('*/v1/contracts/:address', async ({ params }) => {
    const c = findContract(String(params.address))
    return c ? HttpResponse.json(c) : notFound('CONTRACT_NOT_FOUND', 'Contrato não encontrado')
  }),

  // O cliente gerado manda PUT pra `updateMonitoredContract` (achado ao mexer neste arquivo pra E22
  // — o handler antigo interceptava PATCH, então a edição de contrato nunca era de fato respondida
  // no modo mock; nenhuma tela chegou a testar isso porque a listagem/detalhe continuam funcionando
  // via GET, só a submissão do form de edição silenciosamente não batia em handler nenhum).
  http.put('*/v1/contracts/:address', async ({ params, request }) => {
    const c = findContract(String(params.address))
    if (!c) return notFound('CONTRACT_NOT_FOUND')
    const body = (await request.json()) as { name: string; description?: string; version?: string }
    Object.assign(c, body)
    return HttpResponse.json(c)
  }),

  http.patch('*/v1/contracts/:address/status', async ({ params, request }) => {
    const c = findContract(String(params.address))
    if (!c) return notFound('CONTRACT_NOT_FOUND')
    const body = (await request.json()) as UpdateContractStatusPayload
    c.status = body.status
    return HttpResponse.json(c)
  }),

  // ---------------- ABI ----------------

  http.get('*/v1/contracts/:address/abi', async ({ params }) => {
    const addr = String(params.address).toLowerCase()
    if (!findContract(addr)) return notFound('CONTRACT_NOT_FOUND')
    return HttpResponse.json(db.abis.filter((a) => a.contractAddress.toLowerCase() === addr))
  }),

  http.post('*/v1/contracts/:address/abi', async ({ params, request }) => {
    const addr = String(params.address).toLowerCase()
    const c = findContract(addr)
    if (!c) return notFound('CONTRACT_NOT_FOUND')
    const body = (await request.json()) as RegisterAbiPayload
    if (body.source === 'UPLOAD_TEXT' && body.abi && body.abi.trim().startsWith('{')) {
      return HttpResponse.json({ code: 'INVALID_ABI_CONTENT', message: 'ABI precisa ser um array JSON de fragmentos', timestamp: new Date().toISOString() }, { status: 400 })
    }
    const abi: ContractAbi = {
      id: helpers.uid(),
      contractAddress: addr,
      network: c.network,
      source: body.source,
      storageType: body.storage ?? 'DATABASE',
      validFromBlock: body.validFromBlock ?? 0,
      createdAt: new Date().toISOString(),
    }
    db.abis.push(abi)
    // "redecode" instantâneo: os eventos raw desse contrato viram decodificados.
    const catalog = eventCatalogs[addr]
    if (catalog?.length) {
      db.events
        .filter((e) => e.contractAddress.toLowerCase() === addr && !e.decoded)
        .forEach((e) => {
          const evt = catalog[Math.floor(Math.random() * catalog.length)]
          e.decoded = true
          e.eventName = evt.name
          e.decodedParameters = Object.fromEntries(
            evt.inputs.map((i) => [i.name, i.type === 'address' ? helpers.WALLET.carol : i.type.includes('[]') ? ['1', '2'] : i.type === 'bool' ? false : '1000']),
          )
        })
    }
    return HttpResponse.json(abi, { status: 201 })
  }),

  http.get('*/v1/contracts/:address/abi/history-plan', async ({ params, request }) => {
    await delay(150)
    const addr = String(params.address).toLowerCase()
    if (!findContract(addr)) return notFound('CONTRACT_NOT_FOUND')
    const source = new URL(request.url).searchParams.get('historySource') ?? 'AUTO'
    const replaceLegacy = new URL(request.url).searchParams.get('replaceBlockscoutLegacy') === 'true'
    return HttpResponse.json(buildHistoryPlan(addr, source, historyImported.has(addr), replaceLegacy))
  }),

  http.post('*/v1/contracts/:address/abi/history-import', async ({ params, request }) => {
    await delay(150)
    const addr = String(params.address).toLowerCase()
    const c = findContract(addr)
    if (!c) return notFound('CONTRACT_NOT_FOUND')
    const body = ((await request.json().catch(() => ({}))) ?? {}) as AbiHistoryImportPayload
    const source = body.historySource ?? 'AUTO'
    const replaceLegacy = body.replaceBlockscoutLegacy === true
    const current = buildHistoryPlan(addr, source, historyImported.has(addr), replaceLegacy)
    if (body.expectedFingerprint && body.expectedFingerprint !== current.fingerprint) {
      return HttpResponse.json(
        { code: 'ABI_HISTORY_PLAN_CHANGED', message: 'O plano mudou desde o dry-run.', timestamp: new Date().toISOString() },
        { status: 409 },
      )
    }
    const toApply = current.items.filter((i) => i.status === 'APPLY')
    if (toApply.length > 0 && replaceLegacy) {
      const removed = new Set((current.legacyVersionsToReplace ?? []).map((l) => l.abiId))
      db.abis = db.abis.filter((a) => !removed.has(a.id))
    }
    const items = current.items.map((i) => {
      if (i.status !== 'APPLY') return i
      const abi: ContractAbi = {
        id: helpers.uid(), contractAddress: addr, network: c.network, source: 'BLOCKSCOUT',
        storageType: body.storage ?? 'DATABASE', validFromBlock: i.validFromBlock, validToBlock: i.validToBlock,
        implementationAddress: i.implementationAddress, createdAt: new Date().toISOString(),
      }
      db.abis.push(abi)
      return { ...i, abiId: abi.id }
    })
    if (toApply.length > 0) historyImported.add(addr)
    return HttpResponse.json({ ...current, items, appliedCount: toApply.length, redecodeStarted: toApply.length > 0 })
  }),

  http.get('*/v1/contracts/:address/abi/suggestions', async ({ params }) => {
    const addr = String(params.address).toLowerCase()
    return HttpResponse.json(interfaceSuggestions[addr] ?? [])
  }),

  http.get('*/v1/contracts/:address/events', async ({ params }) => {
    const addr = String(params.address).toLowerCase()
    if (!findContract(addr)) return notFound('CONTRACT_NOT_FOUND')
    return HttpResponse.json(eventCatalogs[addr] ?? [])
  }),

  // ---------------- Discovery ----------------

  http.get('*/v1/discovery/blockscout/contracts', async () => HttpResponse.json(blockscoutVerified)),

  // ---------------- Eventos + redecode ----------------

  http.get('*/v1/events', async ({ request }) => {
    await delay(120)
    const url = new URL(request.url)
    const ca = lower(url.searchParams.get('contractAddress'))
    const name = url.searchParams.get('eventName')
    const fromBlock = url.searchParams.get('fromBlock')
    const toBlock = url.searchParams.get('toBlock')
    const wallet = lower(url.searchParams.get('walletAddress'))
    let list: IndexedEvent[] = [...db.events].sort((a, b) => b.blockNumber - a.blockNumber)
    if (ca) list = list.filter((e) => e.contractAddress.toLowerCase() === ca)
    if (name) list = list.filter((e) => e.eventName === name)
    if (fromBlock) list = list.filter((e) => e.blockNumber >= Number(fromBlock))
    if (toBlock) list = list.filter((e) => e.blockNumber <= Number(toBlock))
    // Equivalente mock do `jsonb_path_exists` real (E22/DEC-080): casa se o endereço aparecer como
    // valor de QUALQUER parâmetro decodificado, não uma chave fixa — evento raw nunca casa.
    if (wallet) {
      list = list.filter(
        (e) =>
          e.decoded &&
          Object.values(e.decodedParameters ?? {}).some((v) => typeof v === 'string' && v.toLowerCase() === wallet),
      )
    }
    return HttpResponse.json(page(list, url))
  }),

  http.get('*/v1/redecode/status', async () =>
    HttpResponse.json({ running: false, processedCount: 0, decodedCount: 0, remainingCount: 0 }),
  ),
  http.post('*/v1/redecode', async () =>
    HttpResponse.json({ running: false, processedCount: db.events.length, decodedCount: db.events.filter((e) => e.decoded).length, remainingCount: 0 }),
  ),

  // ---------------- Saldos ----------------

  http.get('*/v1/balance-monitors', async ({ request }) => HttpResponse.json(page(db.monitors, new URL(request.url)))),

  http.post('*/v1/balance-monitors', async ({ request }) => {
    const body = (await request.json()) as CreateBalanceMonitorPayload
    const created: BalanceMonitorConfig = { id: helpers.uid(), ...body, active: true, createdAt: new Date().toISOString() }
    db.monitors.unshift(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.get('*/v1/balance-monitors/backfill/status', async () =>
    HttpResponse.json({ running: false, configId: undefined, processedCount: 0, deltaCount: 0, remainingCount: 0, caughtUp: true }),
  ),

  http.post('*/v1/balance-monitors/:id/backfill', async ({ params }) => {
    const m = db.monitors.find((x) => x.id === params.id)
    if (m && !db.balances.some((b) => b.contractAddress === m.contractAddress && b.walletAddress === helpers.WALLET.carol)) {
      db.balances.push({ walletAddress: helpers.WALLET.carol, contractAddress: m.contractAddress, network: m.network, balance: '73000', updatedAt: new Date().toISOString() })
    }
    return HttpResponse.json({ running: false, configId: params.id, processedCount: 42, deltaCount: 42, remainingCount: 0, caughtUp: true }, { status: 202 })
  }),

  http.get('*/v1/balance-monitors/:id', async ({ params }) => {
    const m = db.monitors.find((x) => x.id === params.id)
    return m ? HttpResponse.json(m) : notFound('BALANCE_MONITOR_NOT_FOUND')
  }),

  http.patch('*/v1/balance-monitors/:id', async ({ params, request }) => {
    const m = db.monitors.find((x) => x.id === params.id)
    if (!m) return notFound('BALANCE_MONITOR_NOT_FOUND')
    Object.assign(m, await request.json())
    return HttpResponse.json(m)
  }),

  http.patch('*/v1/balance-monitors/:id/status', async ({ params, request }) => {
    const m = db.monitors.find((x) => x.id === params.id)
    if (!m) return notFound('BALANCE_MONITOR_NOT_FOUND')
    m.active = ((await request.json()) as UpdateBalanceMonitorStatusPayload).active
    return HttpResponse.json(m)
  }),

  http.get('*/v1/balances', async ({ request }) => {
    const url = new URL(request.url)
    const w = lower(url.searchParams.get('walletAddress'))
    const ca = lower(url.searchParams.get('contractAddress'))
    let list = db.balances
    if (w) list = list.filter((b) => b.walletAddress.toLowerCase() === w)
    if (ca) list = list.filter((b) => b.contractAddress.toLowerCase() === ca)
    return HttpResponse.json(page(list, url))
  }),

  http.get('*/v1/balances/history', async ({ request }) => {
    const url = new URL(request.url)
    const date = url.searchParams.get('date') ?? helpers.NOW.slice(0, 10)
    const w = lower(url.searchParams.get('walletAddress'))
    const ca = lower(url.searchParams.get('contractAddress'))
    let list = db.balances
    if (w) list = list.filter((b) => b.walletAddress.toLowerCase() === w)
    if (ca) list = list.filter((b) => b.contractAddress.toLowerCase() === ca)
    const snapshots = list.map((b) => ({
      walletAddress: b.walletAddress,
      contractAddress: b.contractAddress,
      network: b.network,
      tokenId: b.tokenId,
      balance: String(Math.round(Number(b.balance) * 0.8)),
      snapshotDate: date,
      blockNumber: 150000,
    }))
    return HttpResponse.json(page(snapshots, url))
  }),

  // ---------------- Carteiras (E22) ----------------

  http.get('*/v1/wallets', async ({ request }) => {
    await delay(120)
    const url = new URL(request.url)
    const network = url.searchParams.get('network')
    let list = db.wallets
    if (network) list = list.filter((w) => w.network === network)
    return HttpResponse.json(page(list, url))
  }),

  http.post('*/v1/wallets', async ({ request }) => {
    const body = (await request.json()) as CreateWalletPayload
    if (findWallet(body.address)) {
      return HttpResponse.json({ code: 'WALLET_ALREADY_EXISTS', message: 'Carteira já cadastrada', timestamp: new Date().toISOString() }, { status: 409 })
    }
    const created: Wallet = {
      id: helpers.uid(),
      address: body.address.toLowerCase(),
      label: body.label,
      description: body.description,
      network: body.network,
      status: 'ACTIVE',
      // Mesma heurística do backend real (`eth_getCode`): aqui, "tem bytecode" = é um dos endereços de
      // contrato já cadastrados no seed — o suficiente pra demonstrar o aviso sem simular RPC de verdade.
      looksLikeContract: db.contracts.some((c) => c.address.toLowerCase() === body.address.toLowerCase()),
      looksLikeContractAcknowledged: false,
    }
    db.wallets.unshift(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  // Precisa vir ANTES de `/v1/wallets/:address` — senão "suggestions" seria capturado como endereço.
  http.get('*/v1/wallets/suggestions', async ({ request }) => {
    const url = new URL(request.url)
    const network = url.searchParams.get('network')
    const known = new Set(db.wallets.map((w) => w.address.toLowerCase()))
    let balances = db.balances
    if (network) balances = balances.filter((b) => b.network === network)
    const candidates = [...new Set(balances.map((b) => b.walletAddress.toLowerCase()))].filter((a) => !known.has(a))
    return HttpResponse.json(candidates)
  }),

  http.get('*/v1/wallets/:address', async ({ params }) => {
    const w = findWallet(String(params.address))
    return w ? HttpResponse.json(w) : notFound('WALLET_NOT_FOUND', 'Carteira não encontrada')
  }),

  http.put('*/v1/wallets/:address', async ({ params, request }) => {
    const w = findWallet(String(params.address))
    if (!w) return notFound('WALLET_NOT_FOUND')
    const body = (await request.json()) as UpdateWalletPayload
    Object.assign(w, body)
    return HttpResponse.json(w)
  }),

  http.patch('*/v1/wallets/:address/status', async ({ params, request }) => {
    const w = findWallet(String(params.address))
    if (!w) return notFound('WALLET_NOT_FOUND')
    w.status = ((await request.json()) as UpdateWalletStatusPayload).status
    return HttpResponse.json(w)
  }),

  http.patch('*/v1/wallets/:address/contract-warning', async ({ params }) => {
    const w = findWallet(String(params.address))
    if (!w) return notFound('WALLET_NOT_FOUND')
    w.looksLikeContractAcknowledged = true
    return HttpResponse.json(w)
  }),

  // ---------------- Webhooks ----------------

  http.get('*/v1/webhooks', async ({ request }) => HttpResponse.json(page(db.webhooks, new URL(request.url)))),

  http.post('*/v1/webhooks', async ({ request }) => {
    const body = (await request.json()) as CreateWebhookPayload
    const created: WebhookConfig = { id: helpers.uid(), ...body, filters: body.filters ?? [], active: true, createdAt: new Date().toISOString() }
    db.webhooks.unshift(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.get('*/v1/webhooks/:id/deliveries', async ({ params, request }) => {
    const list = db.deliveries.filter((d) => d.webhookConfigId === params.id)
    return HttpResponse.json(page(list, new URL(request.url)))
  }),

  http.get('*/v1/webhooks/:id', async ({ params }) => {
    const w = db.webhooks.find((x) => x.id === params.id)
    return w ? HttpResponse.json(w) : notFound('WEBHOOK_NOT_FOUND')
  }),

  http.patch('*/v1/webhooks/:id', async ({ params, request }) => {
    const w = db.webhooks.find((x) => x.id === params.id)
    if (!w) return notFound('WEBHOOK_NOT_FOUND')
    Object.assign(w, await request.json())
    return HttpResponse.json(w)
  }),

  http.patch('*/v1/webhooks/:id/status', async ({ params, request }) => {
    const w = db.webhooks.find((x) => x.id === params.id)
    if (!w) return notFound('WEBHOOK_NOT_FOUND')
    w.active = ((await request.json()) as UpdateWebhookStatusPayload).active
    return HttpResponse.json(w)
  }),

  // ---------------- control-panel (aba "Demonstração") ----------------

  http.get(`${CONTROL_PANEL}/health`, async () => HttpResponse.json({ ok: true, running: null })),

  http.get(`${CONTROL_PANEL}/actions`, async () =>
    HttpResponse.json([
      { id: 'deploy', label: 'Deploy dos contratos de teste', description: 'Copia os .sol pro clone do scaffold-eth-2 e roda yarn deploy --tags TestContracts.', params: [], commandTemplate: 'cp contracts/*.sol <clone>/… && (cd <clone> && yarn deploy --tags TestContracts)' },
      { id: 'emit-events', label: 'Disparar eventos de teste', description: 'Roda seed/emit-events.ts nos 4 contratos. ROUNDS repete o lote inteiro.', params: [{ name: 'rounds', label: 'Rounds', type: 'number', default: 1 }], commandTemplate: 'ROUNDS=1 npx tsx seed/emit-events.ts' },
      { id: 'register-contracts', label: 'Registrar os 4 contratos no Evex', description: 'Roda seed/register-contracts.ts: POST /v1/contracts + POST /abi pros 4 de uma vez.', params: [], commandTemplate: 'EVEX_API_URL=http://localhost:8080 npx tsx seed/register-contracts.ts' },
    ]),
  ),

  http.post(`${CONTROL_PANEL}/run/:id`, async ({ params, request }) => {
    const body = (await request.json().catch(() => ({}))) as { rounds?: number }
    const rounds = body.rounds ?? 1
    const cmd = params.id === 'emit-events' ? `ROUNDS=${rounds} npx tsx seed/emit-events.ts` : `npx tsx seed/${params.id}.ts`
    const lines = [
      ...(params.id === 'emit-events'
        ? Array.from({ length: rounds }, (_, r) => [
            `Round ${r + 1}/${rounds} — disparando eventos...`,
            '  ✔ TestERC20.mint', '  ✔ TestERC721.safeMint', '  ✔ TestERC1155.mint', '  ✔ TestEventEmitter.createWidget',
          ]).flat()
        : ['  ✔ ação concluída (mock)']),
      'Pronto.',
    ]
    const stream = new ReadableStream({
      async start(controller) {
        const enc = new TextEncoder()
        controller.enqueue(enc.encode(`event: started\ndata: ${JSON.stringify({ action: params.id, command: cmd })}\n\n`))
        for (const line of lines) {
          await delay(120)
          controller.enqueue(enc.encode(`event: line\ndata: ${JSON.stringify(line)}\n\n`))
        }
        await delay(120)
        controller.enqueue(enc.encode(`event: done\ndata: ${JSON.stringify({ exitCode: 0 })}\n\n`))
        controller.close()
      },
    })
    return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
  }),

  // Streams SSE de status: o `EventSource` nativo não é interceptável pelo MSW — deixamos falhar
  // e o `useServerSentStatus` cai pro polling dos endpoints REST acima (comportamento documentado).
  http.get(`${CONTROL_PANEL}/run/*`, () => new HttpResponse(null, { status: 404 })),

  // Evita ruído: qualquer outra rota do control-panel volta 404 silencioso.
  http.all(`${CONTROL_PANEL}/*`, () => new HttpResponse(null, { status: 404 })),
]

export { ADDR }
