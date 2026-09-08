/**
 * E15 · Fluxo 1 — Cadastro de contrato monitorado + ABI por texto colado (doc §4.1).
 *
 * Não depende de contrato on-chain: o Evex não valida existência na rede no cadastro (E2/E3).
 * Usa um endereço aleatório novo a cada run → não colide com estado preexistente.
 */
import { test, expect } from '../fixtures/test'

const MINIMAL_ERC20_ABI = JSON.stringify([
  {
    anonymous: false,
    type: 'event',
    name: 'Transfer',
    inputs: [
      { indexed: true, name: 'from', type: 'address' },
      { indexed: true, name: 'to', type: 'address' },
      { indexed: false, name: 'value', type: 'uint256' },
    ],
  },
])

function randomAddress(): string {
  const hex = Array.from({ length: 40 }, () => Math.floor(Math.random() * 16).toString(16)).join('')
  return `0x${hex}`
}

test('cadastra contrato monitorado e registra ABI colada como texto', async ({ page, contractsPage, contractDetailPage }) => {
  const address = randomAddress()
  await page.goto('/')

  // --- cadastro do contrato ---
  await contractsPage.open()
  const form = await contractsPage.goToNewContract()
  await form.fill({ address, name: `e2e-${address.slice(2, 8)}`, network: 'TESTNET' })
  await form.submit()

  // redireciona pro detalhe do contrato recém-criado
  await expect(page).toHaveURL(new RegExp(`/contracts/${address}`, 'i'))
  await expect(page.getByRole('heading', { level: 5 })).toContainText('e2e-')
  await expect(page.getByText('TESTNET')).toBeVisible()

  // --- cadastro da ABI ---
  await expect(page.getByText(/Nenhuma versão de ABI cadastrada ainda/)).toBeVisible()
  const abiDialog = await contractDetailPage.openAbiRegisterDialog()
  await abiDialog.pasteAbi(MINIMAL_ERC20_ABI)
  await abiDialog.submit()

  // a versão aparece na tabela, marcada como vigente e origem UPLOAD_TEXT
  const versionRow = contractDetailPage.abiVersionRow('UPLOAD_TEXT')
  await expect(versionRow).toBeVisible()
  await expect(versionRow).toContainText('vigente')
})

test('rejeita ABI malformada com erro na própria modal', async ({ page, contractsPage, contractDetailPage }) => {
  const address = randomAddress()
  await contractsPage.open()
  const form = await contractsPage.goToNewContract()
  await form.fill({ address, name: `e2e-bad-${address.slice(2, 8)}` })
  await form.submit()
  await expect(page).toHaveURL(new RegExp(`/contracts/${address}`, 'i'))

  const abiDialog = await contractDetailPage.openAbiRegisterDialog()
  await abiDialog.pasteAbi('{ not valid json')
  await page.getByRole('button', { name: 'Cadastrar versão de ABI' }).click()

  // a modal continua aberta e mostra o erro do backend (INVALID_ABI_CONTENT → 400)
  await expect(page.getByRole('dialog', { name: 'Cadastrar nova versão de ABI' })).toBeVisible()
  await expect(page.getByRole('dialog').getByRole('alert')).toBeVisible()
})
