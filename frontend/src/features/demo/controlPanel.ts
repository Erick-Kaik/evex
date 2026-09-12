/**
 * Cliente do `local-env/control-panel` (E17, DEC-075) — serviço HTTP mínimo que dispara os scripts
 * do `local-env/` (deploy dos contratos de teste, emissão de eventos, registro no Evex).
 *
 * **Outra origem, `fetch` direto (não o cliente gerado):** o control-panel roda em `:8899`, é
 * ferramenta de ambiente local e **não tem spec OpenAPI**. A regra "sempre o cliente gerado" do
 * `frontend/CLAUDE.md` vale para o backend do Evex (que tem `.yaml`); aqui não há o que gerar.
 * Também não faz parte do produto — a aba "Demonstração" só existe quando `VITE_ENABLE_DEMO_TAB='1'`
 * **E** `VITE_LOCALENV_CONTROL_URL` está setada **E** o serviço responde ao health check.
 *
 * O flag `VITE_ENABLE_DEMO_TAB` é deliberadamente separado da URL (em vez de só checar a URL): a
 * aba só faz sentido contra um RPC local (dispara scripts que fazem deploy/mint/etc. no `local-env/`,
 * não em uma rede real) — um opt-in explícito evita expor a aba por engano caso alguém configure a
 * URL do control-panel num ambiente que não seja este `local-env` (ex.: um deploy usando um RPC real).
 */

/** `'1'` liga a aba "Demonstração" — precisa estar ligado além da URL estar setada e saudável. */
export function isDemoTabEnabled(): boolean {
  return import.meta.env.VITE_ENABLE_DEMO_TAB === '1'
}

/** URL base do control-panel, lida em runtime (permite `vi.stubEnv` nos testes). */
export function controlPanelUrl(): string | undefined {
  return import.meta.env.VITE_LOCALENV_CONTROL_URL
}

export interface ControlPanelHealth {
  ok: boolean
  running: string | null
}

export interface ControlPanelActionParam {
  name: string
  label: string
  type: 'number'
  default: number
}

export interface ControlPanelAction {
  id: string
  label: string
  description: string
  params: ControlPanelActionParam[]
  /** Comando exato que a ação vai rodar — exibido sempre, antes de executar (DEC-075). */
  commandTemplate: string
}

/** `GET {url}/health` — resolve só quando o serviço responde `{ ok: true }`. */
export async function fetchControlPanelHealth(): Promise<ControlPanelHealth> {
  const base = controlPanelUrl()
  if (!base) {
    throw new Error('VITE_LOCALENV_CONTROL_URL não configurada')
  }
  const response = await fetch(`${base}/health`)
  if (!response.ok) {
    throw new Error(`control-panel respondeu ${response.status}`)
  }
  const body = (await response.json()) as ControlPanelHealth
  if (!body.ok) {
    throw new Error('control-panel indisponível')
  }
  return body
}

/** `GET {url}/actions` — descreve os botões da aba. */
export async function fetchControlPanelActions(): Promise<ControlPanelAction[]> {
  const base = controlPanelUrl()
  if (!base) {
    throw new Error('VITE_LOCALENV_CONTROL_URL não configurada')
  }
  const response = await fetch(`${base}/actions`)
  if (!response.ok) {
    throw new Error(`control-panel respondeu ${response.status}`)
  }
  return (await response.json()) as ControlPanelAction[]
}

/** Handlers do stream SSE de `POST {url}/run/<id>`. */
export interface RunActionHandlers {
  onStarted?: (payload: { action: string; command: string }) => void
  onLine: (line: string) => void
  onDone: (payload: { exitCode: number }) => void
  onError: (message: string) => void
}

interface SseFrame {
  event: string
  data: string
}

function parseFrame(raw: string): SseFrame | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim())
    }
  }
  if (dataLines.length === 0) {
    return null
  }
  return { event, data: dataLines.join('\n') }
}

/**
 * `POST {url}/run/<id>` (body `{ <param>: <number> }`) → `text/event-stream`. Consumido via `fetch`
 * + leitura incremental do `ReadableStream` (o `EventSource` nativo só faz GET, e a rota é POST com
 * body). Passe um `AbortSignal` para cancelar (o servidor mata o processo filho ao fechar a conexão).
 */
export async function runControlPanelAction(
  id: string,
  params: Record<string, number>,
  handlers: RunActionHandlers,
  signal: AbortSignal,
): Promise<void> {
  const base = controlPanelUrl()
  if (!base) {
    handlers.onError('VITE_LOCALENV_CONTROL_URL não configurada')
    return
  }

  let response: Response
  try {
    response = await fetch(`${base}/run/${id}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params),
      signal,
    })
  } catch (error) {
    if (signal.aborted) {
      return
    }
    handlers.onError(error instanceof Error ? error.message : 'falha ao contatar o control-panel')
    return
  }

  if (response.status === 409) {
    handlers.onError('Já há uma ação em execução no control-panel — aguarde ela terminar.')
    return
  }
  if (!response.ok || !response.body) {
    handlers.onError(`control-panel respondeu ${response.status}`)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const chunks = buffer.split('\n\n')
      buffer = chunks.pop() ?? ''
      for (const chunk of chunks) {
        const frame = parseFrame(chunk)
        if (!frame) {
          continue
        }
        dispatchFrame(frame, handlers)
      }
    }
  } catch (error) {
    if (!signal.aborted) {
      handlers.onError(error instanceof Error ? error.message : 'stream interrompido')
    }
  }
}

function dispatchFrame(frame: SseFrame, handlers: RunActionHandlers): void {
  switch (frame.event) {
    case 'started': {
      try {
        handlers.onStarted?.(JSON.parse(frame.data) as { action: string; command: string })
      } catch {
        // ignora frame malformado
      }
      return
    }
    case 'line': {
      try {
        handlers.onLine(JSON.parse(frame.data) as string)
      } catch {
        handlers.onLine(frame.data)
      }
      return
    }
    case 'done': {
      try {
        const payload = JSON.parse(frame.data) as { exitCode?: number }
        handlers.onDone({ exitCode: payload.exitCode ?? -1 })
      } catch {
        handlers.onDone({ exitCode: -1 })
      }
      return
    }
  }
}
