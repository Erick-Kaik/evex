/**
 * Configuração do cliente gerado (typescript-axios, `npm run generate:api`) — único arquivo
 * handwritten dentro de `src/api/` (o resto é gerado pelo openapi-generator a partir do bundle
 * Redocly de `../backend/src/main/resources/swagger/base/base.yaml`, nunca editado à mão, nunca
 * commitado — ver frontend/CLAUDE.md e `.gitignore`).
 *
 * Isto NÃO é uma chamada HTTP manual (regra não-negociável do projeto): é só a configuração de
 * transporte (`basePath`) que a própria `Configuration` do cliente gerado espera receber.
 *
 * Desde a E8.1 o backend expõe CORS de verdade via Spring Security
 * (`indexer.cors.allowed-origins`, default `http://localhost:5173`) — o default abaixo aponta
 * direto para o backend (porta padrão do `bootRun`), sem depender do proxy opcional do Vite
 * (`vite.config.ts`).
 */
import { Configuration } from './configuration'

export const API_BASE_PATH =
  import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export const apiConfiguration = new Configuration({
  basePath: API_BASE_PATH,
})
