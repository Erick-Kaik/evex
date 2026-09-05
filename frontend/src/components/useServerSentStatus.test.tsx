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
    expect(FakeEventSource.last().closed).toBe(true)
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
})
