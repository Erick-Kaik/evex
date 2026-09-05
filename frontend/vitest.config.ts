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
  },
})
