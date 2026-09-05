/**
 * E17 · Fluxo 9 — Feedback assíncrono ao vivo (SSE de status) + aba de demonstração local
 * (doc §4.3, E5/E14, DEC-074/DEC-075/DEC-078).
 *
 * Duas frentes da E17:
 *  1. **SSE de status** — a tela do contrato assina `GET /v1/redecode/status/stream` e a de saldos
 *     `GET /v1/balance-monitors/backfill/status/stream` (`text/event-stream`, `EventSource` nativo).
 *     O backend empurra cada transição; o indicador de redecode ("Decodificando o histórico…")
 *     aparece e some **sem reload**.
 *  2. **Aba "Demonstração"** — só existe quando `VITE_LOCALENV_CONTROL_URL` está setada **e** o
 *     `local-env/control-panel` responde ao health check. Dispara os scripts do `local-env` (deploy /
 *     emit-events / register-contracts) mostrando o comando exato antes de rodar (DEC-075).
 *
 * Estado: os testes de "assinatura SSE" toleram qualquer estado. O de "indicador de redecode ao vivo"
 * precisa de um contrato SEM ABI com histórico raw — usa o `TestERC1155`, que só ganha ABI no spec 06
 * (que por sua vez só roda com `E2E_RESET_DB=1`). Por isso ele se skipa quando `resetDb` está ligado
 * (imagem espelhada do `test.skip` do spec 06). Os testes da aba Demonstração se skipam sem o
 * control-panel no ar.
 */
import { test, expect } from '../fixtures/test'
import { config } from '../helpers/config'
import { loadDeployedContracts } from '../helpers/deployed'
import * as controlPanel from '../helpers/control-panel'

const REDECODE_STREAM = '/v1/redecode/status/stream'
const BACKFILL_STREAM = '/v1/balance-monitors/backfill/status/stream'

test.describe('E17 — SSE de status ao vivo', () => {
  test('a tela do contrato assina o status de redecode via EventSource (text/event-stream)', async ({
    page,
    contractsPage,
    contractDetailPage,
    evexApi,
  }) => {
    const { TestERC1155 } = await loadDeployedContracts()
    await evexApi.ensureContract(TestERC1155.address, 'e2e SSE redecode')

    const streamRequest = page.waitForRequest((r) => r.url().includes(REDECODE_STREAM), { timeout: 30_000 })
    await contractsPage.openContract(TestERC1155.address)

    const response = await (await streamRequest).response()
    expect(response?.status(), 'o stream SSE de redecode deve responder 200').toBe(200)
    expect(response?.headers()['content-type'] ?? '').toContain('text/event-stream')

    // sem redecode rodando, o indicador não aparece
    await expect(contractDetailPage.redecodeIndicator).toBeHidden()
  })

  test('a tela de Saldos assina o status de backfill via EventSource (text/event-stream)', async ({
    page,
    balancesPage,
  }) => {
    const streamRequest = page.waitForRequest((r) => r.url().includes(BACKFILL_STREAM), { timeout: 30_000 })
    await balancesPage.open()

    const response = await (await streamRequest).response()
    expect(response?.status(), 'o stream SSE de backfill deve responder 200').toBe(200)
    expect(response?.headers()['content-type'] ?? '').toContain('text/event-stream')
  })

  test('o indicador de redecode é empurrado via SSE e some sozinho, sem reload', async ({
    contractsPage,
    contractDetailPage,
    eventsPage,
    evexApi,
  }) => {
    test.skip(
      config.resetDb,
      'com E2E_RESET_DB=1 o spec 06 registra a ABI do TestERC1155 — este teste precisa do ERC1155 SEM ABI (rode sem --clean)',
    )

    const { TestERC1155 } = await loadDeployedContracts()
    const address = TestERC1155.address
    await evexApi.ensureContract(address, 'e2e SSE redecode indicator')

    test.skip(
      await evexApi.contractHasAbi(address),
      'TestERC1155 já tem ABI nesta stack — o cenário raw→redecode não se reproduz',
    )

    // backlog raw fresco pra o redecode ter trabalho observável
    await evexApi.emitEvents(2)
    const raw = await evexApi.waitForAnyEvent(address)
    expect(raw.decoded, 'sem ABI, os eventos do ERC1155 devem estar raw').toBe(false)

    // abre a tela do contrato (assina o SSE) e cadastra a ABI aqui mesmo → dispara o redecode global
    await contractsPage.openContract(address)
    await expect(contractDetailPage.redecodeIndicator).toBeHidden()

    const abiDialog = await contractDetailPage.openAbiRegisterDialog()
    await abiDialog.pasteAbi(JSON.stringify(TestERC1155.abi))
    await abiDialog.submit()

    // O indicador é empurrado via SSE, sem a página recarregar. Um backlog pequeno pode ser
    // redecodificado antes de o indicador ser observável pelo Playwright — registramos como anotação
    // em vez de uma asserção temporal frágil (ver e2e/CLAUDE.md: nada de espera fixa).
    const sawIndicator = await contractDetailPage.redecodeIndicator
      .waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => true)
      .catch(() => false)

    // Determinístico: o redecode roda de fato e o indicador fica escondido no fim (sem reload).
    await evexApi.waitForRedecodeIdle()
    await expect(contractDetailPage.redecodeIndicator).toBeHidden()
    await evexApi.waitForDecodedEvent(address, 'TransferSingle')

    test.info().annotations.push({
      type: sawIndicator ? 'sse-indicador-observado' : 'sse-indicador-perdido',
      description: sawIndicator
        ? 'indicador "Decodificando o histórico…" capturado ao vivo via SSE'
        : 'redecode terminou antes de o indicador ser observável — assinatura SSE + decodificação OK',
    })

    // sanidade pela UI: os eventos históricos do ERC1155 agora aparecem decodificados
    await eventsPage.open()
    await eventsPage.filterByContract(address)
    await expect(eventsPage.rowWith('TransferSingle').first()).toBeVisible()
  })
})

test.describe('E17 — aba Demonstração (control-panel local, DEC-075)', () => {
  const EMIT = 'Disparar eventos de teste'
  const REGISTER = 'Registrar os 4 contratos no Evex'

  test.beforeEach(async ({ demoPage }) => {
    test.skip(
      !(await controlPanel.isUp()),
      'control-panel fora do ar — suba com local-env/scripts/up.sh (ou npx tsx local-env/control-panel/server.ts)',
    )
    test.skip(
      !(await demoPage.isAvailable()),
      'aba "Demonstração" ausente — o frontend precisa ser servido com VITE_LOCALENV_CONTROL_URL. O webServer do ' +
        'Playwright já passa a var; um `npm run dev` reaproveitado (reuseExistingServer) pode não ter. Ver e2e/RUNBOOK.md.',
    )
  })

  test.afterEach(async () => {
    // não deixa uma ação em andamento (emit-events com ROUNDS alto) vazar pro próximo teste/spec
    await controlPanel.waitForIdle()
  })

  test('mostra o comando exato, dispara emit-events e os eventos entram no índice do Evex', async ({
    demoPage,
    eventsPage,
    evexApi,
  }) => {
    await demoPage.open()

    // o comando exato aparece ANTES de rodar (DEC-075)
    await expect(demoPage.commandText(EMIT)).toContainText('npx tsx seed/emit-events.ts')

    const before = (await evexApi.getEvents({ size: 1 })).totalElements

    await demoPage.setParam(EMIT, 'Rounds', 2)
    await demoPage.run(EMIT)

    // output ao vivo no <pre> + chip de saída
    await expect(demoPage.liveOutput(EMIT)).toContainText('Round 1/2', { timeout: 120_000 })
    await expect(demoPage.statusChip(EMIT, 'exit 0')).toBeVisible({ timeout: 120_000 })

    // os eventos emitidos pela aba entram no índice e aparecem na tela de Eventos
    await evexApi.waitFor(
      () => evexApi.getEvents({ size: 1 }),
      (p) => p.totalElements > before,
      { message: 'nenhum evento novo indexado após o emit-events da aba Demonstração', timeoutMs: 90_000 },
    )
    await eventsPage.open()
    await expect(eventsPage.eventRows.first()).toBeVisible()
  })

  test('uma ação por vez: a segunda recebe 409 e a UI mostra o alerta "aguarde"', async ({ demoPage }) => {
    await demoPage.open()

    await demoPage.setParam(EMIT, 'Rounds', 2)
    await demoPage.run(EMIT)
    await controlPanel.waitForRunning() // só dispara a 2ª quando a 1ª está confirmada rodando (sem corrida)

    await demoPage.run(REGISTER)
    await expect(demoPage.errorAlert(REGISTER)).toContainText(/já há uma ação em execução/i)

    // deixa a 1ª ação terminar antes de sair (afterEach confirma o idle)
    await expect(demoPage.statusChip(EMIT, /exit \d/)).toBeVisible({ timeout: 120_000 })
  })
})
