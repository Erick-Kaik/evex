/**
 * E15 · Fluxo 6 — Backfill de saldo sob demanda (doc §4.4, E14 / DEC-066 / DEC-068).
 *
 * Cenário: config de saldo criada DEPOIS de o histórico já ter sido decodificado → a projeção ao vivo
 * nunca reprocessa esses eventos → `GET /v1/balances` fica vazio até o backfill rodar.
 *
 * Usa o `TestERC1155` (`TransferSingle`) de propósito: nenhum outro spec cria config de monitoramento
 * pra ele, então o "saldo vazio antes do backfill" se sustenta mesmo com a suíte em série (o `05`
 * deixa configs ativas pra `TestERC20`/`TestERC721`).
 *
 * Precisa de estado limpo (a marca d'água / "saldo vazio antes" não se sustenta se um run anterior já
 * populou `asset_balances` pro mesmo par carteira+contrato). Roda só com `E2E_RESET_DB=1`.
 */
import { test, expect } from '../fixtures/test'
import { config } from '../helpers/config'
import { loadDeployedContracts } from '../helpers/deployed'

const RECIPIENT = (process.env.E2E_RECIPIENT ?? '0x70997970c51812dc3a010c7d01b50e0d17dc79c8').toLowerCase()
const TOKEN_ID = '1' // `emit-events.ts` faz `TestERC1155.mint(RECIPIENT, id=1, amount, "0x")`

test.skip(!config.resetDb, 'Fluxo de backfill exige estado limpo — rode a suíte com E2E_RESET_DB=1')

test('backfill reprojeta o histórico já decodificado contra uma config criada depois', async ({
  page,
  balancesPage,
  evexApi,
}) => {
  const { TestERC1155 } = await loadDeployedContracts()
  const address = TestERC1155.address

  // 1. contrato + ABI + eventos → redecode decodifica o histórico (sem nenhuma config de saldo ainda)
  await evexApi.ensureContract(address, 'e2e backfill ERC1155')
  await evexApi.emitEvents(2)
  await evexApi.waitForAnyEvent(address)
  await evexApi.ensureAbi(address, JSON.stringify(TestERC1155.abi))
  await evexApi.waitForRedecodeIdle()
  await evexApi.waitForDecodedEvent(address, 'TransferSingle')

  // 2. config de saldo criada AGORA (depois do redecode) → saldo vazio
  //    E17/DEC-074: a UI assina o status do backfill via SSE (EventSource), não polling — o card
  //    "Reprocessamento de histórico" só existe no detalhe de uma configuração (E18 tirou o backfill
  //    da listagem), então a assinatura acontece quando `createMonitor` termina lá, não em `/balances`.
  const backfillStream = page.waitForRequest(
    (r) => r.url().includes('/v1/balance-monitors/backfill/status/stream'),
    { timeout: 30_000 },
  )
  await balancesPage.open()
  await balancesPage.createMonitor({
    contract: address,
    eventName: 'TransferSingle',
    behavior: 'CREDIT',
    walletParam: 'to',
    valueParam: 'value',
    tokenIdParam: 'id',
  })
  const streamResponse = await (await backfillStream).response()
  expect(streamResponse?.headers()['content-type'] ?? '').toContain('text/event-stream')

  const empty = await evexApi.getBalances({ walletAddress: RECIPIENT, contractAddress: address })
  expect(empty.content, 'saldo deve estar vazio antes do backfill').toHaveLength(0)

  // 3. dispara o backfill pela UI — `createMonitor` já deixou a página no detalhe desta configuração
  await balancesPage.triggerBackfill()

  // progresso inline aparece e depois some (ou vira "Reprocessamento concluído.")
  await expect(balancesPage.backfillProgressText).toBeVisible({ timeout: 30_000 })

  // 4. espera o job global terminar e o saldo aparecer
  await evexApi.waitForBackfillIdle()
  const filled = await evexApi.waitFor(
    () => evexApi.getBalances({ walletAddress: RECIPIENT, contractAddress: address, tokenId: TOKEN_ID }),
    (p) => p.content.length > 0 && BigInt(p.content[0]!.balance) > 0n,
    { message: 'saldo não apareceu após o backfill', timeoutMs: 60_000 },
  )
  const balanceAfter = BigInt(filled.content[0]!.balance)

  // 5. re-disparar é no-op (não conta em dobro)
  await balancesPage.triggerBackfill()
  await evexApi.waitForBackfillIdle()
  const again = await evexApi.getBalances({ walletAddress: RECIPIENT, contractAddress: address, tokenId: TOKEN_ID })
  expect(BigInt(again.content[0]!.balance)).toBe(balanceAfter)

  // 6. status global reporta caught up
  const status = await evexApi.getBackfillStatus()
  expect(status.caughtUp).toBe(true)

  // 7. a tela de Saldos mostra o saldo reprojetado
  await balancesPage.open()
  await balancesPage.query({ wallet: RECIPIENT, contract: address })
  await expect(balancesPage.balanceRow(RECIPIENT)).toContainText(balanceAfter.toString())
})
