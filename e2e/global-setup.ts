/**
 * Roda uma vez antes de toda a suíte:
 *  1. checa que a stack (Hardhat + backend + frontend, e Blockscout se configurado) está no ar;
 *  2. opcionalmente reseta o banco do Evex (`E2E_RESET_DB=1`) e aborta se o `AbiCache` do backend
 *     estiver velho (DEC-070 — o truncate não invalida o cache, precisa reiniciar o `bootRun`);
 *  3. opcionalmente dispara um round de eventos de teste na rede (`E2E_RUN_SEED=1`).
 *
 * Falha cedo, com mensagem que aponta pros scripts do `local-env/`, em vez de deixar cada spec
 * quebrar com timeout.
 *
 * O seed **não** registra contratos/ABIs no Evex — cada spec estabelece as próprias precondições via
 * `evexApi.ensureContract`/`ensureAbi`, e os specs 03 (auto-detecção) e 04 (raw→decodificado)
 * dependem justamente do estado "contrato sem ABI"; pré-registrar as 4 ABIs globalmente quebraria
 * esses fluxos. Ver DEC-069/DEC-070 (revisado na 1ª execução real da suíte).
 */
import { config } from './helpers/config'
import { assertStackReady } from './helpers/stack'
import { assertAbiCacheFresh, emitEvents, resetEvexDb } from './helpers/evex-api'

export default async function globalSetup(): Promise<void> {
  await assertStackReady()

  if (config.resetDb) {
    console.log('[e2e] E2E_RESET_DB=1 → truncando tabelas do Evex...')
    await resetEvexDb()
    // Aborta cedo (com a instrução de reiniciar o bootRun) se o AbiCache em memória estiver velho —
    // o truncate não o invalida (DEC-070). Sem isso, os specs 03/04 falham de forma confusa ~40s depois.
    await assertAbiCacheFresh()
  }

  if (config.runSeed) {
    console.log('[e2e] E2E_RUN_SEED=1 → disparando um round de eventos de teste na rede...')
    await emitEvents(1)
  }
}
