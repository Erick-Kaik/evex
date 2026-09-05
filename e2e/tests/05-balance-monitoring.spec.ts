/**
 * E15 · Fluxo 5 — Monitoramento de saldo ao vivo (doc §4.4, E7).
 *
 * Config de saldo criada ANTES dos eventos → a projeção ao vivo (na ingestão/redecode) alimenta
 * `asset_balances`. Assertivas monotônicas ("saldo aumentou") em vez de igualdade exata, pra tolerar
 * estado preexistente quando a suíte roda sem `E2E_RESET_DB=1`.
 *
 * E16: o formulário "Nova configuração" agora é **guiado** por padrão — contrato via
 * `<ContractAutocomplete>` (sem `<NetworkSelect>` — a rede é herdada), evento via `<EventPicker>`,
 * cada parâmetro num `<EventParamSelect>` filtrado por tipo. Nenhum `0x…` nem nome de parâmetro é
 * digitado. O helper `balancesPage.createMonitor` usa o modo guiado por padrão.
 */
import { test, expect } from '../fixtures/test'
import { loadDeployedContracts } from '../helpers/deployed'

const RECIPIENT = (process.env.E2E_RECIPIENT ?? '0x70997970c51812dc3a010c7d01b50e0d17dc79c8').toLowerCase()

test('config de saldo CREDIT (modo guiado) projeta os Transfer de mint na tela de Saldos', async ({
  balancesPage,
  evexApi,
}) => {
  const { TestERC20 } = await loadDeployedContracts()
  const address = TestERC20.address

  // precondição: contrato + ABI (pra o <EventPicker>/<EventParamSelect> terem catálogo, e decodificar ao vivo)
  await evexApi.ensureContract(address, 'e2e balance ERC20')
  await evexApi.ensureAbi(address, JSON.stringify(TestERC20.abi))

  // cria a config de monitoramento PELA UI (modo guiado), antes de emitir
  await balancesPage.open()
  await balancesPage.createMonitor({
    contract: address,
    eventName: 'Transfer',
    behavior: 'CREDIT',
    walletParam: 'to',
    valueParam: 'value',
  })

  // o modo guiado montou o payload certo: rede herdada do contrato + params escolhidos por select
  const monitor = await evexApi.waitForBalanceMonitor(address, 'Transfer')
  expect(monitor.network, 'a rede é herdada do contrato (não é pedida no modo guiado)').toBe('TESTNET')
  expect(monitor.walletParam).toBe('to')
  expect(monitor.valueParam).toBe('value')

  const before = await evexApi.getBalances({ walletAddress: RECIPIENT, contractAddress: address })
  const beforeBalance = BigInt(before.content[0]?.balance ?? '0')

  // emite mints (Transfer 0x0 -> RECIPIENT) e espera decodificar
  await evexApi.emitEvents(1)
  await evexApi.waitForDecodedEvent(address, 'Transfer')

  await evexApi.waitFor(
    () => evexApi.getBalances({ walletAddress: RECIPIENT, contractAddress: address }),
    (p) => BigInt(p.content[0]?.balance ?? '0') > beforeBalance,
    { message: 'saldo do recipient não aumentou após o mint', timeoutMs: 60_000 },
  )

  // a tela de Saldos mostra o saldo consultado (via <ContractAutocomplete>)
  await balancesPage.open()
  await balancesPage.query({ wallet: RECIPIENT, contract: address })
  const row = balancesPage.balanceRow(RECIPIENT)
  await expect(row).toBeVisible()
  await expect(row).toContainText(address.slice(0, 10))
})

test('consulta de saldo pelo fallback de endereço livre ("digitar endereço manualmente")', async ({
  balancesPage,
  evexApi,
}) => {
  const { TestERC20 } = await loadDeployedContracts()
  const address = TestERC20.address
  await evexApi.ensureContract(address, 'e2e balance ERC20')

  // o saldo do RECIPIENT já foi creditado no teste anterior — aqui a asserção é que o fallback
  // manual (TextField "0x…") produz a mesma consulta que o autocomplete.
  await balancesPage.open()
  await balancesPage.query({ wallet: RECIPIENT, contract: address, contractManual: true })
  await expect(balancesPage.balanceRow(RECIPIENT)).toBeVisible()
})

test('pausar e reativar uma config de monitoramento pela UI', async ({ balancesPage, evexApi }) => {
  const { TestERC721 } = await loadDeployedContracts()
  const address = TestERC721.address
  await evexApi.ensureContract(address, 'e2e balance ERC721')
  await evexApi.ensureAbi(address, JSON.stringify(TestERC721.abi))

  await balancesPage.open()
  await balancesPage.createMonitor({
    contract: address,
    eventName: 'Transfer',
    behavior: 'CREDIT',
    walletParam: 'to',
    valueParam: 'tokenId',
  })

  const row = balancesPage.monitorRow(address)
  await expect(row).toContainText('sim') // ativa
  await row.getByRole('button', { name: 'Pausar' }).click()
  await expect(row).toContainText('não')
  await row.getByRole('button', { name: 'Ativar' }).click()
  await expect(row).toContainText('sim')
})
