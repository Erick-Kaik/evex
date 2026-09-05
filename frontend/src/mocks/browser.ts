/**
 * Service Worker do MSW pro modo mock (`npm run dev:mock`). Só é importado quando
 * `import.meta.env.VITE_MOCK === '1'` (ver `main.tsx`). Não faz parte do produto.
 */
import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

export const worker = setupWorker(...handlers)

/** Inicia o worker. `onUnhandledRequest: 'bypass'` deixa assets do Vite e o `EventSource` dos
 *  streams SSE (não interceptáveis) passarem sem poluir o console. */
export async function startMockWorker(): Promise<void> {
  await worker.start({ onUnhandledRequest: 'bypass', quiet: true })

  console.info('%c[mock] Evex rodando com dados estáticos (MSW). Recarregar a página zera o estado.', 'color:#1e293b;font-weight:600')
}
