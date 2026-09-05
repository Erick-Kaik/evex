/**
 * Camada fina de acesso à API do Evex pra os testes E2E — **só** pra esperas determinísticas
 * (polling de `GET /v1/events`, `GET /v1/redecode/status`, `GET /v1/balance-monitors/backfill/status`)
 * e setup/reset de estado. Nunca reimplementa regra de negócio: a asserção do comportamento acontece
 * pela UI, nos `.spec.ts`. Isto aqui é só a "linha do tempo" pra o teste saber quando olhar a tela.
 */
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { config } from './config'

const execFileAsync = promisify(execFile)

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(`${config.apiUrl}${path}`, { signal: AbortSignal.timeout(15_000) })
  if (!res.ok) throw new Error(`GET ${path} → ${res.status} ${await res.text()}`)
  return (await res.json()) as T
}

async function apiPost(path: string, body: unknown, okStatuses = [200, 201, 202]): Promise<Response> {
  const res = await fetch(`${config.apiUrl}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  })
  if (!okStatuses.includes(res.status)) throw new Error(`POST ${path} → ${res.status} ${await res.text()}`)
  return res
}

// ---------- Precondições (setup de estado que NÃO é o alvo do teste) ----------

/** Cadastra o contrato se ainda não existe (tolera 409). Não usa a UI de propósito — precondição. */
export async function ensureContract(address: string, name: string, network = 'TESTNET'): Promise<void> {
  await apiPost(
    '/v1/contracts',
    { address, name, description: 'e2e fixture', network },
    [201, 409],
  )
}

/**
 * Cadastra uma versão de ABI (source=UPLOAD_TEXT) se o contrato ainda não tiver nenhuma. Precondição
 * — o cadastro em si é testado pela UI. Idempotente: o backend não devolve 409 pra ABI duplicada, e
 * sim 400 `ABI_VERSION_ORDER_INVALID` (a versão aberta exige `validFromBlock` estritamente crescente),
 * então a "tolerância" é checar antes se já existe alguma versão.
 */
export async function ensureAbi(address: string, abiJson: string): Promise<void> {
  const existing = await apiGet<unknown[]>(`/v1/contracts/${address}/abi`).catch(() => [])
  if (Array.isArray(existing) && existing.length > 0) return
  await apiPost(`/v1/contracts/${address}/abi`, { source: 'UPLOAD_TEXT', abi: abiJson }, [201])
}

/** `true` se o contrato já tem pelo menos uma versão de ABI cadastrada. */
export async function contractHasAbi(address: string): Promise<boolean> {
  const versions = await apiGet<unknown[]>(`/v1/contracts/${address}/abi`).catch(() => [])
  return Array.isArray(versions) && versions.length > 0
}

export interface WaitOptions {
  timeoutMs?: number
  intervalMs?: number
  message?: string
}

/** Faz polling de `predicate` até virar truthy. Sem `waitForTimeout` fixo — condição real. */
export async function waitFor<T>(
  probe: () => Promise<T>,
  predicate: (value: T) => boolean,
  opts: WaitOptions = {},
): Promise<T> {
  const timeoutMs = opts.timeoutMs ?? 60_000
  const intervalMs = opts.intervalMs ?? 2_000
  const deadline = Date.now() + timeoutMs
  let last: T | undefined
  while (Date.now() < deadline) {
    last = await probe()
    if (predicate(last)) return last
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  throw new Error(
    `${opts.message ?? 'Condição não satisfeita'} dentro de ${timeoutMs}ms. Último valor: ${JSON.stringify(last)}`,
  )
}

// ---------- Eventos ----------

export interface EventDto {
  id: string
  contractAddress: string
  eventName?: string
  blockNumber: number
  txHash: string
  decoded: boolean
  decodedParameters?: Record<string, unknown>
}
export interface Page<T> {
  content: T[]
  page: number
  totalElements: number
  totalPages: number
}

export async function getEvents(params: Record<string, string | number> = {}): Promise<Page<EventDto>> {
  const qs = new URLSearchParams(Object.entries(params).map(([k, v]) => [k, String(v)]))
  return apiGet<Page<EventDto>>(`/v1/events?${qs.toString()}`)
}

/** Espera existir pelo menos 1 evento (raw ou decodificado) do contrato. */
export async function waitForAnyEvent(contractAddress: string): Promise<EventDto> {
  const page = await waitFor(
    () => getEvents({ contractAddress, size: 50 }),
    (p) => p.content.length > 0,
    { message: `Nenhum evento indexado para ${contractAddress}`, timeoutMs: 90_000 },
  )
  return page.content[0]!
}

/** Espera existir pelo menos 1 evento DECODIFICADO com o nome dado (pós-redecode). */
export async function waitForDecodedEvent(contractAddress: string, eventName: string): Promise<EventDto> {
  const page = await waitFor(
    () => getEvents({ contractAddress, eventName, size: 50 }),
    (p) => p.content.some((e) => e.decoded && e.eventName === eventName),
    { message: `Nenhum evento ${eventName} decodificado para ${contractAddress}`, timeoutMs: 90_000 },
  )
  return page.content.find((e) => e.decoded && e.eventName === eventName)!
}

// ---------- Redecode / Backfill status ----------

export interface RedecodeStatus {
  running: boolean
  [k: string]: unknown
}
export async function getRedecodeStatus(): Promise<RedecodeStatus> {
  return apiGet<RedecodeStatus>('/v1/redecode/status')
}
export async function waitForRedecodeIdle(): Promise<void> {
  await waitFor(getRedecodeStatus, (s) => !s.running, { message: 'Redecode não ficou ocioso', timeoutMs: 90_000 })
}

export interface BackfillStatus {
  running: boolean
  configId: string | null
  processedCount: number
  deltaCount: number
  remainingCount: number
  caughtUp: boolean
}
export async function getBackfillStatus(): Promise<BackfillStatus> {
  return apiGet<BackfillStatus>('/v1/balance-monitors/backfill/status')
}
export async function waitForBackfillIdle(): Promise<BackfillStatus> {
  return waitFor(getBackfillStatus, (s) => !s.running, {
    message: 'Backfill de saldo não ficou ocioso',
    timeoutMs: 90_000,
  })
}

// ---------- Balances ----------

export interface BalanceDto {
  walletAddress: string
  contractAddress: string
  tokenId?: string
  balance: string
}
export async function getBalances(params: Record<string, string> = {}): Promise<Page<BalanceDto>> {
  const qs = new URLSearchParams(params)
  return apiGet<Page<BalanceDto>>(`/v1/balances?${qs.toString()}`)
}

// ---------- Configs de saldo / webhooks (só pra conferir o payload que a UI montou) ----------
//
// A asserção do comportamento continua sendo pela UI (mensagem de sucesso, linha na tabela). Estes
// GETs servem só pra confirmar que o modo guiado/visual do formulário (E16) montou o payload certo —
// rede herdada do contrato, nomes de parâmetro escolhidos por select/canvas em vez de digitados.

export interface BalanceMonitorDto {
  id: string
  contractAddress: string
  network: string
  eventName: string
  behavior: string
  walletParam?: string
  fromParam?: string
  toParam?: string
  valueParam?: string
  tokenIdParam?: string
  active: boolean
}

export async function getBalanceMonitors(): Promise<Page<BalanceMonitorDto>> {
  return apiGet<Page<BalanceMonitorDto>>('/v1/balance-monitors?page=0&size=100')
}

/** Espera aparecer uma config de saldo pro par contrato+evento e devolve a mais recente. */
export async function waitForBalanceMonitor(
  contractAddress: string,
  eventName: string,
): Promise<BalanceMonitorDto> {
  const target = contractAddress.toLowerCase()
  const page = await waitFor(
    getBalanceMonitors,
    (p) => p.content.some((m) => m.contractAddress.toLowerCase() === target && m.eventName === eventName),
    { message: `Nenhuma config de saldo para ${contractAddress}/${eventName}`, timeoutMs: 30_000 },
  )
  return page.content
    .filter((m) => m.contractAddress.toLowerCase() === target && m.eventName === eventName)
    .at(-1)!
}

export interface WebhookDto {
  id: string
  contractAddress: string
  network: string
  eventName: string
  url: string
  filters?: Array<{ paramName: string; operator: string; value: string }>
  payloadTemplate?: string
  active: boolean
}

export async function getWebhooks(): Promise<Page<WebhookDto>> {
  return apiGet<Page<WebhookDto>>('/v1/webhooks?page=0&size=100')
}

/** Espera aparecer um webhook pro par contrato+evento apontando pra `url` e devolve o mais recente. */
export async function waitForWebhook(
  contractAddress: string,
  eventName: string,
  url: string,
): Promise<WebhookDto> {
  const target = contractAddress.toLowerCase()
  const page = await waitFor(
    getWebhooks,
    (p) =>
      p.content.some(
        (w) => w.contractAddress.toLowerCase() === target && w.eventName === eventName && w.url === url,
      ),
    { message: `Nenhum webhook para ${contractAddress}/${eventName} → ${url}`, timeoutMs: 30_000 },
  )
  return page.content
    .filter((w) => w.contractAddress.toLowerCase() === target && w.eventName === eventName && w.url === url)
    .at(-1)!
}

// ---------- Seed / reset (roda os scripts tsx do local-env) ----------

/**
 * Roda um script `.ts` do `local-env/seed/` com o `tsx` instalado neste módulo (`e2e/node_modules`).
 * `cwd` fica em `e2e/` pra o `npx` achar o tsx local; o script resolve os próprios caminhos via
 * `import.meta.dirname`, então não importa de onde é chamado.
 */
async function runSeedScript(script: 'emit-events.ts' | 'register-contracts.ts', extraEnv: Record<string, string>): Promise<void> {
  const scriptPath = `${config.localEnvDir}/seed/${script}`
  const e2eDir = new URL('..', import.meta.url).pathname
  await execFileAsync('npx', ['--no-install', 'tsx', scriptPath], {
    cwd: e2eDir,
    env: { ...process.env, RPC_URL: config.rpcUrl, CHAIN_ID: config.chainId, EVEX_API_URL: config.apiUrl, ...extraEnv },
    timeout: 180_000,
  })
}

/** Dispara N rounds de eventos reais nos contratos de teste (`local-env/seed/emit-events.ts`). */
export async function emitEvents(rounds = 1): Promise<void> {
  await runSeedScript('emit-events.ts', { ROUNDS: String(rounds) })
}

/** Registra os 4 contratos de teste no Evex via API (`local-env/seed/register-contracts.ts`). */
export async function registerTestContracts(): Promise<void> {
  await runSeedScript('register-contracts.ts', {})
}

/**
 * Trunca todas as tabelas do Evex (menos `flyway_schema_history`) via `docker exec ... psql`.
 * Só pra `E2E_RESET_DB=1` — o local-env é descartável por design (ver local-env/CLAUDE.md).
 */
export async function resetEvexDb(): Promise<void> {
  const sql = `DO $$ DECLARE r RECORD; BEGIN
    FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history') LOOP
      EXECUTE 'TRUNCATE TABLE public.' || quote_ident(r.tablename) || ' RESTART IDENTITY CASCADE';
    END LOOP;
  END $$;`
  await execFileAsync('docker', ['exec', config.dbContainer, 'psql', '-U', 'evex', '-d', 'evex', '-v', 'ON_ERROR_STOP=1', '-c', sql], {
    timeout: 30_000,
  })
}

/**
 * Falha (hard) se o `AbiCache` em memória do backend estiver velho depois de um `resetEvexDb()`
 * (DEC-070): o truncate apaga `contract_abis` mas não o cache (populado só no boot, sem evict). Sem
 * contrato nenhum cadastrado, nada deveria decodificar — se o poller re-indexa e algum evento volta
 * `decoded:true`, o cache tem ABIs "fantasma" e os specs 03/04 quebram de forma confusa 40s depois.
 * Melhor abortar aqui com a instrução certa (reiniciar o `bootRun`).
 */
export async function assertAbiCacheFresh(): Promise<void> {
  const contracts = await apiGet<{ content: unknown[] }>('/v1/contracts?page=0&size=1')
  if (contracts.content.length > 0) return // banco não estava limpo — decodificação legítima, não é o cache

  // dá ~15s pro poller re-ingerir alguns blocos a partir do zero (cursor foi truncado)
  for (let i = 0; i < 6; i++) {
    await new Promise((r) => setTimeout(r, 2_500))
    const events = await getEvents({ size: 50 })
    if (events.content.some((e) => e.decoded)) {
      throw new Error(
        'AbiCache do backend está velho (DEC-070): há eventos DECODIFICADOS logo após truncar o banco, ' +
          'sem nenhum contrato cadastrado. O `E2E_RESET_DB=1` não invalida o cache em memória de ABIs ' +
          '(populado só no boot). REINICIE o backend (`./gradlew bootRun`) antes de rodar a suíte com ' +
          'reset — em CI (backend novo a cada run) isso não acontece.',
      )
    }
  }
}
