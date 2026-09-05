/**
 * Health checks das peças da stack que a suíte E2E precisa no ar. Usado pelo `global-setup.ts` pra
 * falhar cedo com mensagem clara (apontando pros scripts do `local-env/`) em vez de deixar cada spec
 * quebrar com timeout obscuro.
 */
import { config } from './config'

type Check = { name: string; ok: boolean; detail: string; fixHint: string }

async function jsonRpc(url: string, method: string): Promise<unknown> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params: [] }),
    signal: AbortSignal.timeout(4000),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const body = (await res.json()) as { result?: unknown; error?: unknown }
  if (body.error) throw new Error(JSON.stringify(body.error))
  return body.result
}

async function checkHardhat(): Promise<Check> {
  const fixHint = `Rode 'yarn chain --hostname 0.0.0.0' em local-env/local-env-scaffold-eth-2/ (o --hostname é obrigatório pro Blockscout, em container, alcançar o nó — ver local-env/README §3.1).`
  try {
    const block = await jsonRpc(config.rpcUrl, 'eth_blockNumber')
    return { name: 'Hardhat (local-env)', ok: true, detail: `bloco ${Number(block)}`, fixHint }
  } catch (e) {
    return { name: 'Hardhat (local-env)', ok: false, detail: String(e), fixHint }
  }
}

async function checkBackend(): Promise<Check> {
  const fixHint = `Suba o backend do Evex (IDE ou 'cd backend && ./gradlew bootRun') com EVEX_RPC_URL=${config.rpcUrl}${config.blockscoutUrl ? ` e EVEX_BLOCKSCOUT_URL=${config.blockscoutUrl}` : ''}.`
  try {
    const res = await fetch(`${config.apiUrl}/actuator/health`, { signal: AbortSignal.timeout(4000) })
    const body = (await res.json()) as { status?: string }
    const ok = res.ok && body.status === 'UP'
    return { name: 'Backend do Evex', ok, detail: `/actuator/health → ${body.status ?? res.status}`, fixHint }
  } catch (e) {
    return { name: 'Backend do Evex', ok: false, detail: String(e), fixHint }
  }
}

async function checkFrontend(): Promise<Check> {
  const fixHint = `Suba o frontend ('cd frontend && npm run dev') — ou deixe o Playwright subir sozinho (webServer no playwright.config.ts).`
  try {
    const res = await fetch(config.baseUrl, { signal: AbortSignal.timeout(4000) })
    return { name: 'Frontend do Evex', ok: res.ok, detail: `HTTP ${res.status}`, fixHint }
  } catch (e) {
    return { name: 'Frontend do Evex', ok: false, detail: String(e), fixHint }
  }
}

/** Blockscout é opcional — só checa se `E2E_BLOCKSCOUT_URL` estiver setado. */
async function checkBlockscout(): Promise<Check | null> {
  if (!config.blockscoutUrl) return null
  const fixHint = `Rode 'local-env/scripts/blockscout-up.sh' e espere a indexação (curl ${config.blockscoutUrl}/api/v2/stats → total_blocks > 0).`
  try {
    const res = await fetch(`${config.blockscoutUrl}/api/v2/stats`, { signal: AbortSignal.timeout(4000) })
    const body = (await res.json()) as { total_blocks?: string }
    const blocks = Number(body.total_blocks ?? '0')
    return {
      name: 'Blockscout (local-env)',
      ok: res.ok && blocks > 0,
      detail: `total_blocks=${blocks}${blocks === 0 ? ' (indexou zero — nó Hardhat provavelmente em 127.0.0.1, não 0.0.0.0)' : ''}`,
      fixHint,
    }
  } catch (e) {
    return { name: 'Blockscout (local-env)', ok: false, detail: String(e), fixHint }
  }
}

export async function assertStackReady(): Promise<void> {
  // Hardhat + backend + frontend são sempre obrigatórios. Blockscout é opcional (specs de discovery
  // se skipam sozinhas via `isBlockscoutUsable`) — aqui só reporta, não aborta.
  const core = await Promise.all([checkHardhat(), checkBackend(), checkFrontend()])
  const blockscout = await checkBlockscout()

  const all = blockscout ? [...core, blockscout] : core
  console.log(`[e2e] Pré-checagem da stack:\n${all.map((c) => `  ${c.ok ? '✅' : '❌'} ${c.name}: ${c.detail}`).join('\n')}`)
  if (blockscout && !blockscout.ok) {
    console.warn(`[e2e] ⚠️  Blockscout indisponível — specs de discovery vão ser skipadas.\n     → ${blockscout.fixHint}`)
  }

  const failed = core.filter((c) => !c.ok)
  if (failed.length > 0) {
    const detail = failed.map((c) => `  ❌ ${c.name}: ${c.detail}\n     → ${c.fixHint}`).join('\n')
    throw new Error(
      `Stack E2E não está pronta (${failed.length} peça(s) fora do ar):\n${detail}\n\n` +
        `Guia completo: e2e/README.md · Ambiente: local-env/README.md`,
    )
  }
}

/** `true` se o Blockscout está configurado E indexando — specs de discovery usam pra `test.skip`. */
export async function isBlockscoutUsable(): Promise<boolean> {
  const check = await checkBlockscout()
  return check?.ok ?? false
}
