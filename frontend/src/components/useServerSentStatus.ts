import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { API_BASE_PATH } from '../api/config'

/** De onde o status exibido está vindo agora. */
export type StatusSource = 'sse' | 'polling'

/** Backoff da reconexão manual pra fechamento fatal do `EventSource` — mesmo valor do `WebSocketPollTrigger` (backend, E26/DEC-091). */
const RECONNECT_BACKOFF_MS = 5000

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
  /**
   * Quando informado, o polling de fallback para sozinho enquanto este predicado devolver `true` para
   * o último dado conhecido (ex.: `(status) => status?.running !== true` — job ocioso, nada rodando).
   * Sem isso (default), o polling continua batendo em `pollIntervalMs` indefinidamente enquanto o
   * componente estiver montado em modo degradado — aceitável pra job que sempre termina rápido, mas
   * desperdiça requisição num job global que passa a maior parte do tempo ocioso (achado da análise de
   * 2026-09-07). Uma invalidação de `pollQueryKey` (ex.: ao disparar o job de novo) acorda o polling
   * mesmo já parado — `useQuery` sempre refaz o fetch numa invalidação, independente do
   * `refetchInterval` corrente.
   */
  stopPollingWhenIdle?: (data: T | undefined) => boolean
  /**
   * Backoff da reconexão manual em caso de fechamento fatal do `EventSource` (default
   * [RECONNECT_BACKOFF_MS], 5s — mesmo valor do `WebSocketPollTrigger` do backend, E26/DEC-091). Só
   * existe como opção pra os testes usarem um valor pequeno (mesmo racional de `pollIntervalMs`) —
   * nenhum consumidor de produção precisa mexer nisso.
   */
  reconnectBackoffMs?: number
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
 *
 * **Reconexão — queda transiente (achado da análise de 2026-09-07, corrigido)**: `onerror` **não fecha**
 * o `EventSource` — só atualiza o estado exibido (`source`/`connected`). O próprio browser reconecta
 * sozinho por trás (é o comportamento nativo da spec de SSE: a conexão só para de tentar de novo se
 * alguém chamar `.close()`); quando reconectar, o `addEventListener('open', ...)` abaixo já dispara de
 * novo no mesmo objeto e devolve `source` pra `'sse'` sem nenhum código extra. Antes desta correção,
 * `onerror` fechava a conexão explicitamente — matava a reconexão nativa e prendia a aba em polling
 * REST degradado pro resto da vida do componente, mesmo depois do backend voltar (só se recuperava
 * navegando pra outra tela e voltando, o que remonta o componente).
 *
 * **Reconexão — fechamento fatal (achado da revisão do fix acima, corrigido em seguida)**: a correção
 * de cima cobre a queda transiente, mas a spec de SSE também prevê um caso em que o **browser** desiste
 * sozinho, sem retry nenhum — "fail the connection" (ex.: o servidor responde com status HTTP ≠ 2xx, ou
 * `Content-Type` diferente de `text/event-stream`). Nesse caso `readyState` já vem `CLOSED` no momento
 * do `onerror` (não `CONNECTING`, que é o valor durante uma queda transiente aguardando retry nativo) —
 * sinal de que não vale esperar o browser reconectar sozinho, porque ele não vai. `onerror` detecta
 * esse caso (`es.readyState === EventSource.CLOSED`) e agenda **uma reconexão manual** própria (backoff
 * fixo, mesmo padrão do `WebSocketPollTrigger` do backend, E26/DEC-091): cria um `EventSource` novo pro
 * mesmo `path` depois de [RECONNECT_BACKOFF_MS]. Sem isso, esse caso específico ficaria preso em
 * polling REST pro resto da vida do componente — o mesmo sintoma do achado original, só que por uma
 * causa que a correção de cima não alcança (ali o `EventSource` original continua tentando sozinho; aqui
 * ele já desistiu de vez).
 */
export function useServerSentStatus<T>(
  path: string,
  options: ServerSentStatusOptions<T>,
): ServerSentStatus<T> {
  const {
    pollQueryKey,
    pollQueryFn,
    pollIntervalMs = 1500,
    onDone,
    closeOnDone = true,
    stopPollingWhenIdle,
    reconnectBackoffMs = RECONNECT_BACKOFF_MS,
  } = options

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

    // `disposed`/`reconnectTimer` são o seam da reconexão manual (fechamento fatal, ver KDoc acima) —
    // `connect()` pode se chamar de novo (via `onerror`) depois do efeito já ter "terminado" de rodar,
    // então precisa checar `disposed` antes de criar um `EventSource` novo ou agendar outro timer.
    let disposed = false
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined
    let eventSource: EventSource

    const connect = () => {
      eventSource = new EventSource(`${API_BASE_PATH}${path}`)

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
        // O stream caiu (backend fora do ar, timeout de proxy, etc.) — cai pro polling REST. **Não**
        // fecha o EventSource: se for queda transiente, o browser reconecta sozinho por trás (nativo
        // da spec de SSE) e o listener de 'open' acima já devolve source pra 'sse' quando isso
        // acontecer — nenhum código extra necessário nesse caso.
        setConnected(false)
        setSource('polling')

        // Fechamento fatal: o browser já desistiu (readyState === CLOSED, não CONNECTING) — ele não
        // vai tentar de novo sozinho. Cria uma conexão nova nós mesmos, depois de um backoff.
        if (eventSource.readyState === EventSource.CLOSED && !disposed) {
          reconnectTimer = setTimeout(() => {
            if (!disposed) connect()
          }, reconnectBackoffMs)
        }
      }
    }

    connect()

    return () => {
      disposed = true
      if (reconnectTimer !== undefined) clearTimeout(reconnectTimer)
      eventSource.close()
    }
  }, [path, reconnectBackoffMs])

  const pollingActive = source === 'polling'

  const pollQuery = useQuery({
    queryKey: pollQueryKey,
    queryFn: pollQueryFn,
    enabled: pollingActive,
    refetchInterval: pollingActive
      ? (query) => (stopPollingWhenIdle?.(query.state.data) ? false : pollIntervalMs)
      : false,
  })

  return {
    data: pollingActive ? (pollQuery.data ?? sseData) : sseData,
    source,
    connected,
  }
}
