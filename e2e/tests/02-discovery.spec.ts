/**
 * E15 · Fluxo 2 — Discovery via Blockscout (doc §7).
 *
 * Precisa do Blockscout do local-env no ar E indexando, com os contratos de teste verificados
 * (`local-env/blockscout/verify-contracts.ts`). Skipa (não falha) se `E2E_BLOCKSCOUT_URL` não estiver
 * configurado ou o Blockscout não tiver indexado nada.
 */
import { test, expect } from '../fixtures/test'
import { config } from '../helpers/config'
import { isBlockscoutUsable } from '../helpers/stack'
import { testContractAddress } from '../helpers/deployed'

test.describe('discovery via Blockscout', () => {
  test.skip(!config.blockscoutUrl, 'E2E_BLOCKSCOUT_URL não configurado — rode local-env/scripts/blockscout-up.sh')

  test.beforeEach(async () => {
    test.skip(
      !(await isBlockscoutUsable()),
      'Blockscout não indexou blocos (nó Hardhat provavelmente em 127.0.0.1 e não 0.0.0.0, ou verify-contracts.ts não rodou).',
    )
  })

  test('lista contratos verificados e importa um pro formulário de cadastro', async ({ page, contractsPage }) => {
    const erc20 = await testContractAddress('TestERC20')
    await contractsPage.open()
    const dialog = await contractsPage.openNewContractDialog()

    // a coluna "Importar do Blockscout" lista os contratos verificados
    await expect(dialog.discoveryUnavailable).toBeHidden()
    const item = dialog.discoveryItem(erc20.slice(0, 10))
    await expect(item).toBeVisible({ timeout: 20_000 })

    // clicar no item preenche endereço + nome no formulário
    await item.click()
    await expect(page.getByLabel('Endereço')).toHaveValue(new RegExp(erc20, 'i'))

    await dialog.selectNetwork('TESTNET')
    await dialog.submit()
    await expect(page).toHaveURL(new RegExp(`/contracts/${erc20}`, 'i'))
  })

  test('cadastra ABI buscando direto no Blockscout (source=BLOCKSCOUT)', async ({ page, contractsPage, contractDetailPage, evexApi }) => {
    const erc20 = await testContractAddress('TestERC20')
    await evexApi.ensureContract(erc20, 'e2e discovery ERC20')

    await contractsPage.openContract(erc20)
    const abiDialog = await contractDetailPage.openAbiRegisterDialog()
    await abiDialog.chooseSource('Buscar no Blockscout')
    await page.getByRole('button', { name: 'Cadastrar versão de ABI' }).click()

    await expect(contractDetailPage.abiVersionRow('BLOCKSCOUT')).toBeVisible({ timeout: 20_000 })
  })
})
