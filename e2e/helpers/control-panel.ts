/**
 * Cliente fino do `local-env/control-panel` (E17/DEC-075) pra a suíte E2E — só pra o spec 09 decidir
 * se roda (`isUp`) e pra esperar o serviço ficar ocioso entre ações. A asserção do comportamento
 * (aba aparece, comando exibido, output ao vivo, eventos indexados) acontece pela UI, no `.spec.ts`.
 *
 * Não é o backend do Evex — é outra origem, sem spec OpenAPI (mesma razão do `helpers/webhook-site.ts`).
 */
import { config } from './config'
import { waitFor } from './evex-api'

interface ControlPanelHealth {
  ok: boolean
  running: string | null
}

/** `true` se o control-panel respondeu `{ ok: true }` — o spec 09 usa pra `test.skip`. */
export async function isUp(): Promise<boolean> {
  try {
    const res = await fetch(`${config.controlPanelUrl}/health`, { signal: AbortSignal.timeout(3000) })
    if (!res.ok) return false
    const body = (await res.json()) as ControlPanelHealth
    return body.ok === true
  } catch {
    return false
  }
}

async function getHealth(): Promise<ControlPanelHealth> {
  const res = await fetch(`${config.controlPanelUrl}/health`, { signal: AbortSignal.timeout(3000) })
  if (!res.ok) throw new Error(`control-panel /health → ${res.status}`)
  return (await res.json()) as ControlPanelHealth
}

/** Espera nenhuma ação estar rodando (`running: null`) — pra encadear specs sem colher um 409. */
export async function waitForIdle(): Promise<void> {
  await waitFor(getHealth, (h) => h.running == null, {
    message: 'control-panel não ficou ocioso (alguma ação ainda rodando)',
    timeoutMs: 180_000,
    intervalMs: 2_000,
  })
}

/** Espera alguma ação estar rodando (`running != null`) — pra disparar a 2ª sem corrida no teste de 409. */
export async function waitForRunning(): Promise<void> {
  await waitFor(getHealth, (h) => h.running != null, {
    message: 'nenhuma ação do control-panel começou a rodar',
    timeoutMs: 30_000,
    intervalMs: 500,
  })
}
