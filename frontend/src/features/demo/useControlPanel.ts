import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  controlPanelUrl,
  fetchControlPanelActions,
  fetchControlPanelHealth,
  runControlPanelAction,
} from './controlPanel'
import type { ControlPanelAction } from './controlPanel'

/**
 * Health check do `local-env/control-panel` (E17/DEC-075). React Query, `retry: false`,
 * `staleTime` alto: é infraestrutura local que não muda de disponibilidade a todo momento.
 * `enabled` só quando `VITE_LOCALENV_CONTROL_URL` está setada.
 */
export function useControlPanelHealth() {
  return useQuery({
    queryKey: ['control-panel-health'],
    queryFn: fetchControlPanelHealth,
    enabled: Boolean(controlPanelUrl()),
    retry: false,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  })
}

/**
 * `true` quando a aba "Demonstração" deve aparecer: env var setada **e** o serviço respondeu
 * `{ ok: true }` ao health check. Consumido pelo `RootLayout` (mostra a aba) e pelo guard da rota
 * `/demo` (revela a tela) — o React Query deduplica a chamada entre os dois.
 */
export function useControlPanelAvailable(): boolean {
  const health = useControlPanelHealth()
  return Boolean(controlPanelUrl()) && health.data?.ok === true
}

/** Lista as ações disponíveis (`GET {url}/actions`). */
export function useControlPanelActions() {
  return useQuery({
    queryKey: ['control-panel-actions'],
    queryFn: fetchControlPanelActions,
    enabled: Boolean(controlPanelUrl()),
    retry: false,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  })
}

export type ActionRunState = 'idle' | 'running' | 'done'

export interface ActionRunner {
  state: ActionRunState
  lines: string[]
  exitCode: number | undefined
  error: string | undefined
  run: (action: ControlPanelAction, params: Record<string, number>) => void
}

/**
 * Executa uma ação do control-panel e acumula o output ao vivo. O `run` é chamado de um handler de
 * clique (não de `useEffect`) — dispara o `POST /run/<id>` e lê o `text/event-stream` incremental.
 * Aborta o stream no unmount (o servidor mata o processo filho ao ver a conexão fechar).
 */
export function useActionRunner(): ActionRunner {
  const [state, setState] = useState<ActionRunState>('idle')
  const [lines, setLines] = useState<string[]>([])
  const [exitCode, setExitCode] = useState<number | undefined>(undefined)
  const [error, setError] = useState<string | undefined>(undefined)
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    return () => abortRef.current?.abort()
  }, [])

  const run = useCallback((action: ControlPanelAction, params: Record<string, number>) => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setState('running')
    setLines([])
    setExitCode(undefined)
    setError(undefined)

    void runControlPanelAction(
      action.id,
      params,
      {
        onStarted: (payload) => setLines([`$ ${payload.command}`, '']),
        onLine: (line) => setLines((current) => [...current, line]),
        onDone: (payload) => {
          setExitCode(payload.exitCode)
          setState('done')
        },
        onError: (message) => {
          setError(message)
          setState('done')
        },
      },
      controller.signal,
    )
  }, [])

  return { state, lines, exitCode, error, run }
}
