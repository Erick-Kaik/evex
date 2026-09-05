/**
 * Configuração central da suíte E2E — lida de env vars com defaults (ver `e2e/.env.example`).
 * Nenhum outro arquivo lê `process.env` diretamente: importa daqui. Carregar `e2e/.env` aqui (topo
 * do módulo) garante que qualquer importador — `playwright.config.ts`, `global-setup.ts`, specs — já
 * pega os valores do arquivo.
 */
import dotenv from 'dotenv'

dotenv.config({ path: new URL('../.env', import.meta.url).pathname, quiet: true })

/** Remove a barra final pra montar URLs sem `//`. */
function trimSlash(url: string): string {
  return url.replace(/\/+$/, '')
}

export const config = {
  /** Frontend do Evex (`npm run dev`). */
  baseUrl: trimSlash(process.env.E2E_BASE_URL ?? 'http://localhost:5173'),
  /** Backend do Evex (`./gradlew bootRun`). */
  apiUrl: trimSlash(process.env.E2E_API_URL ?? 'http://localhost:8080'),
  /** Nó Hardhat do local-env. Precisa estar em `0.0.0.0` pra o Blockscout (container) alcançar. */
  rpcUrl: trimSlash(process.env.E2E_RPC_URL ?? 'http://127.0.0.1:8545'),
  /** Blockscout do local-env. Vazio = specs de discovery são skipados. */
  blockscoutUrl: process.env.E2E_BLOCKSCOUT_URL ? trimSlash(process.env.E2E_BLOCKSCOUT_URL) : '',
  /** Postgres do Evex — só o reset opcional usa. */
  dbUrl: process.env.E2E_DB_URL ?? 'postgresql://evex:evex@localhost:5432/evex',
  /** Nome do container do Postgres do local-env (pra `docker exec` no reset). */
  dbContainer: process.env.E2E_DB_CONTAINER ?? 'local-env-evex-postgres-1',
  /** `true` = trunca as tabelas do Evex antes da suíte. */
  resetDb: process.env.E2E_RESET_DB === '1',
  /** `true` = o global-setup roda o seed do local-env antes da suíte. */
  runSeed: process.env.E2E_RUN_SEED === '1',
  /** chainId do local-env (scaffold-eth-2 / Hardhat). */
  chainId: process.env.E2E_CHAIN_ID ?? '31337',
  /**
   * `local-env/control-panel` (E17/DEC-075) — serviço que dispara os scripts do `local-env` pra a aba
   * "Demonstração". Sobe junto no `local-env/scripts/up.sh`. Fora do ar → o spec 09 (aba Demonstração)
   * se skipa sozinho.
   */
  controlPanelUrl: trimSlash(process.env.E2E_CONTROL_PANEL_URL ?? 'http://localhost:8899'),
  /** Caminho de `local-env/` (irmão de `e2e/`) — pra ler `deployedContracts.ts` e rodar o seed. */
  localEnvDir: process.env.E2E_LOCAL_ENV_DIR ?? new URL('../../local-env', import.meta.url).pathname,
} as const

export const scaffoldEthDir = `${config.localEnvDir}/local-env-scaffold-eth-2`
