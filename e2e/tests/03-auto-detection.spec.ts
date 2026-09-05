/**
 * E15 · Fluxo 3 — Detecção automática de interface ERC20/721/1155 (doc §4.5).
 *
 * A detecção compara os `topic0` dos primeiros logs observados do contrato com os templates built-in.
 * Precondição: contrato registrado (sem ABI) + eventos reais já ingeridos. Roda o seed de eventos se
 * necessário.
 */
import { test, expect } from '../fixtures/test'
import { loadDeployedContracts } from '../helpers/deployed'

test('detecta ERC20 a partir dos eventos observados e cadastra como AUTO_DETECTED', async ({
  page,
  contractsPage,
  contractDetailPage,
  evexApi,
}) => {
  const { TestERC20 } = await loadDeployedContracts()
  const address = TestERC20.address

  // precondição: contrato conhecido pelo Evex, SEM ABI, com eventos ingeridos
  await evexApi.ensureContract(address, 'e2e auto-detect ERC20')
  await evexApi.emitEvents(1)
  await evexApi.waitForAnyEvent(address)

  await contractsPage.openContract(address)
  const abiDialog = await contractDetailPage.openAbiRegisterDialog()
  await abiDialog.detectInterfaces()

  // aparece pelo menos a sugestão ERC20 (FULL ou PARTIAL, dependendo de quantos eventos já foram vistos)
  await expect(abiDialog.suggestion('ERC20')).toBeVisible({ timeout: 20_000 })

  // se veio FULL, dá pra confirmar como detecção automática
  const fullBadge = page.getByRole('dialog').getByText('FULL', { exact: true })
  if (await fullBadge.count()) {
    await abiDialog.submit()
    await expect(contractDetailPage.abiVersionRow('AUTO_DETECTED')).toBeVisible()
  } else {
    // PARTIAL: a UI bloqueia o cadastro automático e explica o porquê
    await expect(page.getByText(/Nenhuma sugestão com match total/)).toBeVisible()
  }
})

test('a modal "Ver sugestões automáticas" também lista as interfaces reconhecidas', async ({
  page,
  contractsPage,
  evexApi,
}) => {
  const { TestERC1155 } = await loadDeployedContracts()
  await evexApi.ensureContract(TestERC1155.address, 'e2e suggestions ERC1155')
  await evexApi.emitEvents(1)
  await evexApi.waitForAnyEvent(TestERC1155.address)

  await contractsPage.openContract(TestERC1155.address)
  await page.getByRole('button', { name: 'Ver sugestões automáticas' }).click()
  const dialog = page.getByRole('dialog', { name: 'Sugestões de detecção automática' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('button', { name: 'Detectar interfaces' }).click()
  await expect(dialog.getByText('ERC1155')).toBeVisible({ timeout: 20_000 })
})
