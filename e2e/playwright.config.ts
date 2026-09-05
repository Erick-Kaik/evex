import { defineConfig, devices } from '@playwright/test'
import { config } from './helpers/config' // carrega e2e/.env no topo do módulo

/**
 * Config da suíte E2E do Evex. Ver `e2e/CLAUDE.md` pra a filosofia (dirige o navegador contra a
 * stack real, sem mock) e `e2e/README.md` pra pré-requisitos.
 *
 * O `webServer` sobe SÓ o frontend (leve). Backend + local-env (Hardhat/Blockscout) NÃO são subidos
 * aqui — são pesados e o padrão do projeto é rodá-los à parte (IDE / `local-env/scripts/*`). O
 * `globalSetup` checa que estão no ar e falha cedo com instrução clara se não estiverem.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false, // Evex tem 1 Postgres compartilhado e cursor de indexação global — serial evita corrida entre specs.
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 120_000, // specs esperam indexação/redecode/backfill reais (jobs assíncronos + poll de 5s).
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './global-setup.ts',
  use: {
    baseURL: config.baseUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    cwd: '../frontend',
    url: config.baseUrl,
    // Local: reusa o frontend que você já tem no ar. CI: sobe um do zero (garante versão limpa).
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    // VITE_LOCALENV_CONTROL_URL: a aba "Demonstração" (E17/DEC-075) só aparece se ESTA var estiver
    // setada E o control-panel responder ao health check. Aqui sempre anunciamos a URL; a
    // disponibilidade real (control-panel no ar) é o que decide. Num `npm run dev` reaproveitado
    // (reuseExistingServer local), a var pode não estar — o spec 09 se skipa com instrução.
    env: { VITE_API_BASE_URL: config.apiUrl, VITE_LOCALENV_CONTROL_URL: config.controlPanelUrl },
  },
})
