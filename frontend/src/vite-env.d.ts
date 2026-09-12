/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL base do backend Evex (ex.: http://localhost:8080). Default em src/api/config.ts. */
  readonly VITE_API_BASE_URL?: string
  /**
   * `'1'` liga a aba "Demonstração" (DEC-101) — opt-in explícito e separado da URL abaixo, porque a
   * aba só faz sentido contra um RPC local (dispara scripts de deploy/mint no `local-env/`). Sem
   * isso em `'1'`, a aba não existe mesmo que `VITE_LOCALENV_CONTROL_URL` esteja setada.
   */
  readonly VITE_ENABLE_DEMO_TAB?: string
  /**
   * URL do `local-env/control-panel` (ex.: http://localhost:8899). Quando `VITE_ENABLE_DEMO_TAB='1'`
   * e o serviço responde ao health check, a aba "Demonstração" aparece (E17/DEC-075/DEC-101).
   */
  readonly VITE_LOCALENV_CONTROL_URL?: string
  /**
   * `'1'` liga o modo mock (`npm run dev:mock`): o front sobe sozinho, sem backend, com dados
   * estáticos servidos por um Service Worker do MSW (`src/mocks/`). Nunca setado em produção —
   * o código de mock é importado dinamicamente e não entra no bundle real.
   */
  readonly VITE_MOCK?: string
}
