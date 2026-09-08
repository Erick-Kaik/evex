/**
 * `EventSource` falso para os testes unitários — jsdom não implementa SSE. Registra instâncias num
 * array estático para os testes dispararem frames (`emitStatus`/`emitHeartbeat`/`emitDone`/`fail`).
 * Instalado globalmente em `src/test/setup.ts`; `resetFakeEventSources()` roda no `afterEach`.
 */
type Listener = (event: Event) => void

export class FakeEventSource {
  // Mesmos valores da spec real de EventSource (`EventSource.CONNECTING`/`OPEN`/`CLOSED`, estáticos e
  // de instância) — o hook sob teste (`useServerSentStatus`) compara `es.readyState` contra
  // `EventSource.CLOSED` pra distinguir queda transiente (browser reconecta sozinho) de fechamento
  // fatal (browser desiste, precisa de reconexão manual); sem esses estáticos aqui, `EventSource` fica
  // stubado globalmente como esta classe em teste (`src/test/setup.ts`) e a comparação sempre falharia.
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2

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
    // Um EventSource real fechado (`.close()` chamado) nunca mais dispara evento nenhum — nem de
    // reconexão nativa. Sem essa guarda, um teste que chama `open()`/`emit*()` depois de `close()`
    // passaria mesmo que o código sob teste tivesse fechado a conexão indevidamente (o achado real que
    // motivou esta guarda: `useServerSentStatus` fechava no `onerror`, matando a reconexão nativa).
    if (this.closed) return
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

  /** Queda transiente — `readyState` fica em `CONNECTING` (0), como um browser real prestes a reconectar sozinho. */
  fail(): void {
    this.dispatch('error', new Event('error'))
  }

  /**
   * Fechamento fatal da spec de SSE (ex.: servidor respondeu status ≠ 2xx, ou `Content-Type` errado) —
   * o browser real marca `readyState = CLOSED` e desiste de tentar de novo sozinho, tudo isso *antes*
   * de disparar `error` (é assim que o código sob teste sabe diferenciar os 2 casos). Diferente de
   * `close()`: não seta a flag `closed` (o objeto real ainda dispara esse único `error` — só não
   * dispara mais nada *depois* dele, o que aqui é responsabilidade de quem testa não chamar `emit*`
   * de novo neste mesmo objeto).
   */
  failFatal(): void {
    this.readyState = FakeEventSource.CLOSED
    this.dispatch('error', new Event('error'))
  }
}

export function resetFakeEventSources(): void {
  FakeEventSource.instances = []
}
