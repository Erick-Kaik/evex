import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { FakeEventSource } from '../test/fakeEventSource'
import { useServerSentStatus } from './useServerSentStatus'

interface Status {
  running: boolean
  processedCount: number
}

function Harness({ pollFn }: { pollFn: () => Promise<Status> }) {
  const { data, source, connected } = useServerSentStatus<Status>('/v1/redecode/status/stream', {
    pollQueryKey: ['test-status'],
    pollQueryFn: pollFn,
    pollIntervalMs: 10,
  })
  return (
    <div>
      <span data-testid="source">{source}</span>
      <span data-testid="connected">{String(connected)}</span>
      <span data-testid="processed">{data ? data.processedCount : 'none'}</span>
    </div>
  )
}

function renderHarness(pollFn: () => Promise<Status>) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <Harness pollFn={pollFn} />
    </QueryClientProvider>,
  )
}

describe('useServerSentStatus', () => {
  it('assina o stream e reflete o frame event: status', async () => {
    const pollFn = vi.fn<() => Promise<Status>>()
    renderHarness(pollFn)

    expect(screen.getByTestId('source')).toHaveTextContent('sse')
    expect(FakeEventSource.last().url).toBe('http://localhost:8080/v1/redecode/status/stream')

    act(() => {
      FakeEventSource.last().emitStatus({ running: true, processedCount: 7 })
    })

    expect(screen.getByTestId('processed')).toHaveTextContent('7')
    expect(screen.getByTestId('connected')).toHaveTextContent('true')
    expect(pollFn).not.toHaveBeenCalled()
  })

  it('cai para polling REST no onerror do EventSource', async () => {
    const pollFn = vi.fn<() => Promise<Status>>().mockResolvedValue({ running: false, processedCount: 42 })
    renderHarness(pollFn)

    act(() => {
      FakeEventSource.last().fail()
    })

    await waitFor(() => expect(screen.getByTestId('source')).toHaveTextContent('polling'))
    await waitFor(() => expect(screen.getByTestId('processed')).toHaveTextContent('42'))
    // NÃO fecha (achado corrigido 2026-09-07): fechar mataria a reconexão nativa do browser — o
    // EventSource continua vivo por trás, só o estado exibido muda pra 'polling'.
    expect(FakeEventSource.last().closed).toBe(false)
  })

  it('reconecta sozinho (reconexão nativa do EventSource) e volta pra sse depois de um onerror (achado 2026-09-07)', async () => {
    const pollFn = vi.fn<() => Promise<Status>>().mockResolvedValue({ running: false, processedCount: 0 })
    renderHarness(pollFn)

    const eventSource = FakeEventSource.last()
    act(() => {
      eventSource.fail()
    })
    await waitFor(() => expect(screen.getByTestId('source')).toHaveTextContent('polling'))

    // Simula o browser reconectando sozinho no MESMO objeto (nunca cria um EventSource novo) — só é
    // possível porque o código sob teste não chamou `.close()` no onerror (a guarda em
    // `FakeEventSource.dispatch` faria este `open()` virar no-op se tivesse fechado).
    act(() => {
      eventSource.open()
      eventSource.emitStatus({ running: false, processedCount: 99 })
    })

    await waitFor(() => expect(screen.getByTestId('source')).toHaveTextContent('sse'))
    expect(screen.getByTestId('connected')).toHaveTextContent('true')
    expect(screen.getByTestId('processed')).toHaveTextContent('99')
  })

  it('para de fazer polling quando stopPollingWhenIdle devolve true (achado 2026-09-07)', async () => {
    const pollFn = vi.fn<() => Promise<Status>>().mockResolvedValue({ running: false, processedCount: 1 })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    function IdleHarness() {
      useServerSentStatus<Status>('/v1/redecode/status/stream', {
        pollQueryKey: ['idle-status'],
        pollQueryFn: pollFn,
        pollIntervalMs: 10,
        stopPollingWhenIdle: (status) => status?.running !== true,
      })
      return null
    }

    render(
      <QueryClientProvider client={queryClient}>
        <IdleHarness />
      </QueryClientProvider>,
    )

    act(() => {
      FakeEventSource.last().fail() // cai pro polling REST
    })

    await waitFor(() => expect(pollFn).toHaveBeenCalledTimes(1))
    // `running: false` no 1º resultado — refetchInterval devolve `false` a partir daqui. Espera bem
    // mais que pollIntervalMs (10ms) e confirma que não fez uma 2ª chamada.
    await act(() => new Promise((resolve) => setTimeout(resolve, 100)))
    expect(pollFn).toHaveBeenCalledTimes(1)
  })

  it('continua fazendo polling enquanto stopPollingWhenIdle devolve false (job rodando)', async () => {
    const pollFn = vi.fn<() => Promise<Status>>().mockResolvedValue({ running: true, processedCount: 1 })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    function RunningHarness() {
      useServerSentStatus<Status>('/v1/redecode/status/stream', {
        pollQueryKey: ['running-status'],
        pollQueryFn: pollFn,
        pollIntervalMs: 10,
        stopPollingWhenIdle: (status) => status?.running !== true,
      })
      return null
    }

    render(
      <QueryClientProvider client={queryClient}>
        <RunningHarness />
      </QueryClientProvider>,
    )

    act(() => {
      FakeEventSource.last().fail()
    })

    await waitFor(() => expect(pollFn.mock.calls.length).toBeGreaterThan(1))
  })

  it('chama onDone e fecha o EventSource no frame event: done', async () => {
    const onDone = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    function DoneHarness() {
      useServerSentStatus<Status>('/v1/redecode/status/stream', {
        pollQueryKey: ['x'],
        pollQueryFn: () => Promise.resolve({ running: false, processedCount: 0 }),
        onDone,
      })
      return null
    }

    render(
      <QueryClientProvider client={queryClient}>
        <DoneHarness />
      </QueryClientProvider>,
    )

    act(() => {
      FakeEventSource.last().emitDone()
    })

    expect(onDone).toHaveBeenCalledTimes(1)
    expect(FakeEventSource.last().closed).toBe(true)
  })

  it('fechamento fatal (readyState CLOSED, sem retry nativo) dispara reconexão manual depois do backoff (achado 2026-09-08)', async () => {
    const pollFn = vi.fn<() => Promise<Status>>().mockResolvedValue({ running: false, processedCount: 0 })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    function FatalHarness() {
      const { source } = useServerSentStatus<Status>('/v1/redecode/status/stream', {
        pollQueryKey: ['fatal-status'],
        pollQueryFn: pollFn,
        reconnectBackoffMs: 10,
      })
      return <span data-testid="source">{source}</span>
    }

    render(
      <QueryClientProvider client={queryClient}>
        <FatalHarness />
      </QueryClientProvider>,
    )

    const first = FakeEventSource.last()
    act(() => {
      first.failFatal()
    })

    // Diferente da queda transiente: o objeto original nunca mais vai disparar nada (o browser
    // desistiu de verdade) — o hook precisa ter criado um `EventSource` NOVO, não reaproveitado este.
    await waitFor(() => expect(screen.getByTestId('source')).toHaveTextContent('polling'))
    await waitFor(() => expect(FakeEventSource.instances).toHaveLength(2))
    const second = FakeEventSource.last()
    expect(second).not.toBe(first)

    act(() => {
      second.open()
    })
    await waitFor(() => expect(screen.getByTestId('source')).toHaveTextContent('sse'))
  })
})
