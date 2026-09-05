import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { FakeEventSource, resetFakeEventSources } from './fakeEventSource'

// jsdom não implementa `EventSource` (SSE) — stub global controlável (ver src/test/fakeEventSource.ts).
vi.stubGlobal('EventSource', FakeEventSource)

// jsdom não implementa `matchMedia` — o MUI (`useMediaQuery`, tema responsivo) chama no render.
if (!window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })
}

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  resetFakeEventSources()
})
