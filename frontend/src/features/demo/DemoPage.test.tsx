import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../test/renderWithProviders'
import { RootLayout } from '../../routes/RootLayout'
import { DemoPage } from './DemoPage'

const CONTROL_URL = 'http://localhost:8899'

const ACTIONS = [
  {
    id: 'emit-events',
    label: 'Disparar eventos de teste',
    description: 'Roda seed/emit-events.ts.',
    params: [{ name: 'rounds', label: 'Rounds', type: 'number', default: 1 }],
    commandTemplate: 'ROUNDS=1 npx tsx seed/emit-events.ts',
  },
  {
    id: 'deploy',
    label: 'Deploy dos contratos de teste',
    description: 'Copia os .sol e roda yarn deploy.',
    params: [],
    commandTemplate: 'yarn deploy --tags TestContracts',
  },
]

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response
}

function sseResponse(frames: string[]): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) {
        controller.enqueue(encoder.encode(frame))
      }
      controller.close()
    },
  })
  return { ok: true, status: 200, body: stream } as unknown as Response
}

describe('Aba Demonstração (control-panel)', () => {
  beforeEach(() => {
    vi.stubEnv('VITE_ENABLE_DEMO_TAB', '1')
    vi.stubEnv('VITE_LOCALENV_CONTROL_URL', CONTROL_URL)
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  it('RootLayout: sem env var → aba ausente', () => {
    vi.stubEnv('VITE_LOCALENV_CONTROL_URL', '')
    vi.stubGlobal('fetch', vi.fn())
    renderWithProviders(<RootLayout />)
    expect(screen.queryByRole('tab', { name: 'Demonstração' })).not.toBeInTheDocument()
  })

  it('RootLayout: flag VITE_ENABLE_DEMO_TAB desligado, mesmo com URL setada → aba ausente (DEC-101)', () => {
    vi.stubEnv('VITE_ENABLE_DEMO_TAB', '')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ ok: true, running: null })))
    renderWithProviders(<RootLayout />)
    expect(screen.queryByRole('tab', { name: 'Demonstração' })).not.toBeInTheDocument()
  })

  it('DemoPage: flag VITE_ENABLE_DEMO_TAB desligado → redireciona pra /contracts mesmo com control-panel saudável (DEC-101)', async () => {
    vi.stubEnv('VITE_ENABLE_DEMO_TAB', '')
    vi.stubGlobal(
      'fetch',
      vi.fn((input: string) => {
        if (input.endsWith('/health')) return Promise.resolve(jsonResponse({ ok: true, running: null }))
        if (input.endsWith('/actions')) return Promise.resolve(jsonResponse(ACTIONS))
        return Promise.reject(new Error(`unexpected ${input}`))
      }),
    )
    renderWithProviders(<DemoPage />)
    await waitFor(() => expect(screen.queryByText('Demonstração')).not.toBeInTheDocument())
  })

  it('RootLayout: health check falha → aba ausente', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ error: 'x' }, 500)))
    renderWithProviders(<RootLayout />)
    await waitFor(() => expect(screen.getByText('Evex')).toBeInTheDocument())
    expect(screen.queryByRole('tab', { name: 'Demonstração' })).not.toBeInTheDocument()
  })

  it('RootLayout: health check ok → aba presente', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ ok: true, running: null })),
    )
    renderWithProviders(<RootLayout />)
    expect(await screen.findByRole('tab', { name: 'Demonstração' })).toBeInTheDocument()
  })

  it('DemoPage: lista as ações do control-panel e mostra o comando exato', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: string) => {
        if (input.endsWith('/health')) return Promise.resolve(jsonResponse({ ok: true, running: null }))
        if (input.endsWith('/actions')) return Promise.resolve(jsonResponse(ACTIONS))
        return Promise.reject(new Error(`unexpected ${input}`))
      }),
    )
    renderWithProviders(<DemoPage />)

    expect(await screen.findByText('Disparar eventos de teste')).toBeInTheDocument()
    expect(screen.getByText('ROUNDS=1 npx tsx seed/emit-events.ts')).toBeInTheDocument()
    expect(screen.getByText('yarn deploy --tags TestContracts')).toBeInTheDocument()
  })

  it('DemoPage: "Executar" abre o stream e renderiza as linhas + exit 0', async () => {
    const fetchMock = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/health')) return Promise.resolve(jsonResponse({ ok: true, running: null }))
      if (input.endsWith('/actions')) return Promise.resolve(jsonResponse(ACTIONS))
      if (input.endsWith('/run/emit-events')) {
        expect(init?.method).toBe('POST')
        return Promise.resolve(
          sseResponse([
            'event: started\ndata: {"action":"emit-events","command":"ROUNDS=1 npx tsx seed/emit-events.ts"}\n\n',
            'event: line\ndata: "minted 5 tokens"\n\n',
            'event: line\ndata: "done round 1"\n\n',
            'event: done\ndata: {"exitCode":0}\n\n',
          ]),
        )
      }
      return Promise.reject(new Error(`unexpected ${input}`))
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderWithProviders(<DemoPage />)

    const runButtons = await screen.findAllByRole('button', { name: 'Executar' })
    await user.click(runButtons[0])

    expect(await screen.findByText(/minted 5 tokens/)).toBeInTheDocument()
    expect(screen.getByText(/done round 1/)).toBeInTheDocument()
    expect(await screen.findByText('exit 0')).toBeInTheDocument()
  })
})
