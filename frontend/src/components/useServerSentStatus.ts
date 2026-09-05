import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { API_BASE_PATH } from '../api/config'

/** De onde o status exibido está vindo agora. */
export type StatusSource = 'sse' | 'polling'

export interface ServerSentStatusOptions<T> {
  /**
   * Fallback de polling (comportamento pré-E17): usado quando o `EventSource` falha ou o browser
   * não suporta SSE. `pollQueryKey`/`pollQueryFn` alimentam um `useQuery` no endpoint REST `/status`
   * equivalente ao stream.
   */
  pollQueryKey: readonly unknown[]
  pollQueryFn: () => Promise<T>
  /** Intervalo do polling de fallback, em ms (default 1500 — igual ao polling da E14). */
  pollIntervalMs?: number
  /** Chamado uma vez quando o job termina (frame `event: done`). */
  onDone?: () => void
  /** Fecha o `EventSource` ao receber `event: done` (default `true`). */
  closeOnDone?: boolean
}

export interface ServerSentStatus<T> {
  data: T | undefined
  /** `'sse'` enquanto o stream está de pé; `'polling'` após falha de conexão ou sem suporte. */
  source: StatusSource
  /** `true` depois do primeiro frame (`status`/`heartbeat`/`open`) recebido pelo `EventSource`. */
  connected: boolean
}

/**
 * Assina um stream SSE de status de job (`GET {API_BASE}{path}`, E17/DEC-074/DEC-078) e devolve o
 * último `event: status` recebido — o mesmo DTO dos endpoints REST `/status` (`RedecodeStatus` /
 * `BalanceBackfillStatus`).
 *
 * **Sobre a regra "sem `useEffect` para fetch" do `frontend/CLAUDE.md`:** o `EventSource` é
 * infraestrutura de **transporte** (push do servidor via `text/event-stream`), não um `fetch`
 * imperativo de dados que o React Query deveria gerenciar. O `useEffect` aqui só cuida do
 * ciclo de vida da conexão (criar no mount, fechar no unmount) — não busca dados dentro dele.
 * O caminho de **fetch** de verdade (o fallback REST) continua sendo React Query. Complementa a
 * DEC-074.
 *
 * Fallback: em `onerror` do `EventSource` (ou se `EventSource` não existir no browser), cai para
 * o polling React Query do endpoint `/status` correspondente — sem regressão em relação ao
 * comportamento anterior.
 */
export function useServerSentStatus<T>(
  path: string,
  options: ServerSentStatusOptions<T>,
): ServerSentStatus<T> {
  const { pollQueryKey, pollQueryFn, pollIntervalMs = 1500, onDone, closeOnDone = true } = options

  const hasEventSource = typeof EventSource !== 'undefined'

  const [sseData, setSseData] = useState<T | undefined>(undefined)
  const [source, setSource] = useState<StatusSource>(hasEventSource ? 'sse' : 'polling')
  const [connected, setConnected] = useState(false)

  // Refs para os callbacks: o `useEffect` do `EventSource` só deve depender de `path`, não recriar
  // a conexão a cada render por identidade nova de `onDone`. Sincronizados num efeito à parte
  // (atualizar ref durante o render é proibido pelo lint).
  const onDoneRef = useRef(onDone)
  const closeOnDoneRef = useRef(closeOnDone)
  useEffect(() => {
    onDoneRef.current = onDone
    closeOnDoneRef.current = closeOnDone
  })

  useEffect(() => {
    if (typeof EventSource === 'undefined') {
      // `source` já foi inicializado como 'polling' neste caso — nada a fazer.
      return
    }

    const eventSource = new EventSource(`${API_BASE_PATH}${path}`)

    eventSource.addEventListener('open', () => {
      setConnected(true)
      setSource('sse')
    })

    eventSource.addEventListener('status', (event) => {
      const messageEvent = event as MessageEvent<string>
      setConnected(true)
      setSource('sse')
      try {
        setSseData(JSON.parse(messageEvent.data) as T)
      } catch {
        // frame malformado — ignora, o próximo frame corrige
      }
    })

    eventSource.addEventListener('heartbeat', () => {
      setConnected(true)
    })

    eventSource.addEventListener('done', () => {
      onDoneRef.current?.()
      if (closeOnDoneRef.current) {
        eventSource.close()
      }
    })

    eventSource.onerror = () => {
      // O stream caiu (backend fora do ar, timeout de proxy, etc.). Fecha e cai pro polling REST.
      setConnected(false)
      setSource('polling')
      eventSource.close()
    }

    return () => {
      eventSource.close()
    }
  }, [path])

  const pollingActive = source === 'polling'

  const pollQuery = useQuery({
    queryKey: pollQueryKey,
    queryFn: pollQueryFn,
    enabled: pollingActive,
    refetchInterval: pollingActive ? pollIntervalMs : false,
  })

  return {
    data: pollingActive ? (pollQuery.data ?? sseData) : sseData,
    source,
    connected,
  }
}
