/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** URL base do backend Evex (ex.: http://localhost:8080). Default em src/api/config.ts. */
  readonly VITE_API_BASE_URL?: string
}
