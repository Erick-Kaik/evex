/**
 * `EventSource` falso para os testes unitários — jsdom não implementa SSE. Registra instâncias num
 * array estático para os testes dispararem frames (`emitStatus`/`emitHeartbeat`/`emitDone`/`fail`).
 * Instalado globalmente em `src/test/setup.ts`; `resetFakeEventSources()` roda no `afterEach`.
 */
type Listener = (event: Event) => void

export class FakeEventSource {
  static instances: FakeEventSource[] = []

  static last(): FakeEventSource {
    const instance = FakeEventSource.instances.at(-1)
    if (!instance) {
      throw new Error('nenhum FakeEventSource criado')
    }
    return instance
  }

  url: string
  readyState = 0
  onopen: Listener | null = null
  onerror: Listener | null = null
  onmessage: Listener | null = null
  closed = false
  private listeners = new Map<string, Set<Listener>>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: Listener): void {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set())
    }
    this.listeners.get(type)?.add(listener)
  }

  removeEventListener(type: string, listener: Listener): void {
    this.listeners.get(type)?.delete(listener)
  }

  close(): void {
    this.closed = true
    this.readyState = 2
  }

  private dispatch(type: string, event: Event): void {
    if (type === 'open') this.onopen?.(event)
    if (type === 'error') this.onerror?.(event)
    this.listeners.get(type)?.forEach((listener) => listener(event))
  }

  // --- helpers de teste ---

  open(): void {
    this.readyState = 1
    this.dispatch('open', new Event('open'))
  }

  emit(type: string, data: unknown): void {
    const event = new MessageEvent(type, { data: JSON.stringify(data) })
    this.dispatch(type, event)
  }

  emitStatus(data: unknown): void {
    this.emit('status', data)
  }

  emitHeartbeat(): void {
    this.emit('heartbeat', {})
  }

  emitDone(): void {
    this.emit('done', {})
  }

  fail(): void {
    this.dispatch('error', new Event('error'))
  }
}

export function resetFakeEventSources(): void {
  FakeEventSource.instances = []
}
