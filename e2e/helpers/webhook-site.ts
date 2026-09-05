/**
 * Cliente mínimo da API pública do https://webhook.site — usado pelo spec `07-webhooks` como
 * "endpoint de terceiro" real pra onde o Evex faz o `POST` do webhook. Sem isso o fluxo de webhook
 * (doc §4.6) não teria como ser testado ponta a ponta sem stub.
 *
 * Fluxo: `createToken()` → configura o webhook no Evex apontando pra `endpointUrl(uuid)` → emite um
 * evento → `waitForRequest(uuid, ...)` faz polling até a requisição do Evex chegar no webhook.site.
 *
 * `E2E_WEBHOOK_SITE_BASE` permite trocar por uma instância self-hosted. Sem internet de saída, o spec
 * 07 se skipa via `isReachable()`.
 */
const BASE = (process.env.E2E_WEBHOOK_SITE_BASE ?? 'https://webhook.site').replace(/\/+$/, '')

export interface WebhookSiteRequest {
  uuid: string
  method: string
  content: string
  /** Corpo já parseado como JSON quando `content` é JSON válido; senão `null`. */
  json: unknown
  created_at: string
}

async function http(path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${BASE}${path}`, {
    ...init,
    headers: { accept: 'application/json', ...(init?.headers ?? {}) },
    signal: AbortSignal.timeout(15_000),
  })
}

/** `true` se o webhook.site responde — o spec 07 usa pra `test.skip` quando não há internet. */
export async function isReachable(): Promise<boolean> {
  try {
    const res = await fetch(BASE, { method: 'GET', signal: AbortSignal.timeout(5_000) })
    return res.ok
  } catch {
    return false
  }
}

/** Cria um token anônimo. Retorna o uuid — a URL de destino é `endpointUrl(uuid)`. */
export async function createToken(): Promise<string> {
  const res = await http('/token', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ default_status: 200, default_content: 'ok', cors: true }),
  })
  if (!res.ok) throw new Error(`POST /token → ${res.status} ${await res.text()}`)
  const body = (await res.json()) as { uuid: string }
  return body.uuid
}

/** URL que vai no campo "URL de destino" do webhook no Evex. */
export function endpointUrl(uuid: string): string {
  return `${BASE}/${uuid}`
}

/** Requisições recebidas nesse token, mais recentes primeiro. */
export async function getRequests(uuid: string): Promise<WebhookSiteRequest[]> {
  const res = await http(`/token/${uuid}/requests?sorting=newest&page=1`)
  if (!res.ok) throw new Error(`GET /token/${uuid}/requests → ${res.status} ${await res.text()}`)
  const body = (await res.json()) as { data: Array<Omit<WebhookSiteRequest, 'json'>> }
  return body.data.map((r) => {
    let json: unknown = null
    try {
      json = JSON.parse(r.content)
    } catch {
      /* corpo não-JSON — deixa null */
    }
    return { ...r, json }
  })
}

/** Faz polling até uma requisição recebida bater no `predicate` (ou estoura o timeout). */
export async function waitForRequest(
  uuid: string,
  predicate: (req: WebhookSiteRequest) => boolean,
  opts: { timeoutMs?: number; intervalMs?: number } = {},
): Promise<WebhookSiteRequest> {
  const timeoutMs = opts.timeoutMs ?? 60_000
  const intervalMs = opts.intervalMs ?? 2_500
  const deadline = Date.now() + timeoutMs
  let seen = 0
  while (Date.now() < deadline) {
    const requests = await getRequests(uuid)
    seen = requests.length
    const match = requests.find(predicate)
    if (match) return match
    await new Promise((r) => setTimeout(r, intervalMs))
  }
  throw new Error(`Nenhuma requisição do Evex bateu no predicate em ${timeoutMs}ms (recebidas: ${seen}).`)
}

/** Limpa o token no fim do teste (best-effort). */
export async function deleteToken(uuid: string): Promise<void> {
  try {
    await http(`/token/${uuid}`, { method: 'DELETE' })
  } catch {
    /* best-effort */
  }
}
