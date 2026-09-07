import { defineConfig } from 'vitest/config'

// Config de teste separada do `vite.config.ts` (build) — Vitest + Testing Library + jsdom.
// Introduzido na E16 (passada A do frontend): primeira suíte de testes unitários do frontend.
// Sem `@vitejs/plugin-react` aqui de propósito: os testes não precisam de Fast Refresh e a versão
// de Vite embutida no Vitest 3 briga com a do build (rolldown-vite) na checagem de tipos do plugin.
// `esbuild.jsx: 'automatic'` cobre a transformação de JSX/TSX.
export default defineConfig({
  esbuild: { jsx: 'automatic' },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
    // Default (5000ms) ficou justo pra `WebhookForm.test.tsx` depois da E19/DEC-084 — o form passou
    // a montar 2 `<Autocomplete>` a mais (Perfis de Enriquecimento/Modelos de Entidade) incondicionalmente
    // em todo teste do arquivo, e sob contenção de CPU (múltiplos workers em paralelo) isso empurrava o
    // 1º teste do arquivo pra cima do timeout mesmo passando isolado. 10s dá folga sem mascarar teste
    // realmente travado.
    testTimeout: 10000,
  },
})
