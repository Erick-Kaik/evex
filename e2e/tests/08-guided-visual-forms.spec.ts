/**
 * E16 · Fluxo 8 — Formulários de configuração no modo **visual** (canvas de arrastar-para-vincular) +
 * persistência do `<FormModeToggle>` (DEC-072).
 *
 * O drag real do `@dnd-kit` é instável em navegador headless (e mesmo headed), então este spec usa o
 * **fallback de clique** do `<ParamBindingCanvas>`: clicar num chip de parâmetro o seleciona, clicar
 * num slot compatível cria o vínculo. Os seletores acessíveis são os mesmos do drag
 * (`getByRole('button', { name: 'Parâmetro <nome>, tipo <tipo>' })` / `{ name: 'Campo <label>' }`).
 *
 * Usa o `TestEventEmitter`/`WidgetCreated` (`id uint256`, `creator address`, `name string`,
 * `value uint256`): nenhum outro spec cria config de saldo pra ele. Roda por último na suíte serial.
 */
import { test, expect } from '../fixtures/test'
import { loadDeployedContracts } from '../helpers/deployed'

const WIDGET_CREATED = {
  wallet: { name: 'creator', type: 'address' },
  amount: { name: 'value', type: 'uint256' },
}

test('config de saldo no modo visual: vincula params por clique, recusa tipo incompatível, mesmo payload do guiado', async ({
  balancesPage,
  evexApi,
}) => {
  const { TestEventEmitter } = await loadDeployedContracts()
  const address = TestEventEmitter.address
  await evexApi.ensureContract(address, 'e2e visual EventEmitter')
  await evexApi.ensureAbi(address, JSON.stringify(TestEventEmitter.abi))

  await balancesPage.open()
  await balancesPage.setMonitorFormMode('visual')
  await balancesPage.selectMonitorContract(address) // <ContractAutocomplete> — sem <NetworkSelect>
  await balancesPage.pickMonitorEvent('WidgetCreated')

  // behavior default CREDIT → slots "Carteira" / "Montante/delta" / "Token ID (opcional)"
  await balancesPage.bindParamOnCanvas(WIDGET_CREATED.wallet, 'Carteira')
  await balancesPage.bindParamOnCanvas(WIDGET_CREATED.amount, 'Montante/delta')

  // troca pra TRANSFER → os slots de endereço viram "Origem (from)" / "Destino (to)"
  await balancesPage.setBehavior('TRANSFER')
  await expect(balancesPage.canvasSlot('Origem (from)')).toBeVisible()
  await expect(balancesPage.canvasSlot('Destino (to)')).toBeVisible()

  // tentar vincular um parâmetro numérico num slot de endereço é recusado (sem binding)
  await balancesPage.bindParamOnCanvas(WIDGET_CREATED.amount, 'Origem (from)')
  await expect(balancesPage.canvasTypeMismatch).toBeVisible()
  await expect(balancesPage.canvasSlot('Origem (from)')).toHaveAttribute('aria-label', /vazio/)

  // volta pra CREDIT e submete — o payload é equivalente ao do modo guiado
  await balancesPage.setBehavior('CREDIT')
  await balancesPage.submitMonitor()

  const monitor = await evexApi.waitForBalanceMonitor(address, 'WidgetCreated')
  expect(monitor.network, 'rede herdada do contrato').toBe('TESTNET')
  expect(monitor.behavior).toBe('CREDIT')
  expect(monitor.walletParam).toBe('creator')
  expect(monitor.valueParam).toBe('value')
})

test('webhook no modo visual: filtro pelo canvas + token de payloadTemplate por clique', async ({
  webhooksPage,
  evexApi,
}) => {
  const { TestEventEmitter } = await loadDeployedContracts()
  const address = TestEventEmitter.address
  const url = 'https://example.com/e2e-webhook-visual'
  await evexApi.ensureContract(address, 'e2e visual webhook')
  await evexApi.ensureAbi(address, JSON.stringify(TestEventEmitter.abi))

  await webhooksPage.open()
  await webhooksPage.setFormMode('visual')
  await webhooksPage.selectContract(address)
  await webhooksPage.pickEvent('WidgetCreated')
  await webhooksPage.fillUrl(url)

  // 1 slot de filtro: vincula "value" pelo canvas, define o valor de comparação (operador default EQ)
  await webhooksPage.addFilter()
  await webhooksPage.bindFilterOnCanvas(WIDGET_CREATED.amount, 'Filtro 1')
  await webhooksPage.fillFilterValue('1000')

  // <PayloadTemplateBuilder>: inserir um token por clique entra no template
  await webhooksPage.insertPayloadToken('{{eventName}}')
  await expect(webhooksPage.payloadField).toHaveValue(/\{\{eventName\}\}/)

  await webhooksPage.submit()
  await webhooksPage.expectCreated()

  const webhook = await evexApi.waitForWebhook(address, 'WidgetCreated', url)
  expect(webhook.network, 'rede herdada do contrato').toBe('TESTNET')
  expect(webhook.payloadTemplate ?? '').toContain('{{eventName}}')
  expect(webhook.filters?.[0]?.paramName).toBe('value')
})

test('persistência do <FormModeToggle>: o modo escolhido sobrevive ao reload', async ({
  page,
  balancesPage,
}) => {
  await balancesPage.open()

  await balancesPage.setMonitorFormMode('manual')
  await page.reload()
  await balancesPage.expectMonitorFormMode('manual')

  await balancesPage.setMonitorFormMode('visual')
  await page.reload()
  await balancesPage.expectMonitorFormMode('visual')
})
