/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL base do backend Evex (ex.: http://localhost:8080). Default em src/api/config.ts. */
  readonly VITE_API_BASE_URL?: string
  /**
   * URL do `local-env/control-panel` (ex.: http://localhost:8899). Quando setada e o serviço
   * responde ao health check, a aba "Demonstração" aparece (E17/DEC-075). Sem isso, a aba não existe.
   */
  readonly VITE_LOCALENV_CONTROL_URL?: string
  /**
   * `'1'` liga o modo mock (`npm run dev:mock`): o front sobe sozinho, sem backend, com dados
   * estáticos servidos por um Service Worker do MSW (`src/mocks/`). Nunca setado em produção —
   * o código de mock é importado dinamicamente e não entra no bundle real.
   */
  readonly VITE_MOCK?: string
}
