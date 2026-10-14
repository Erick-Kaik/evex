/**
 * Saldo de eventos em lote (arrays) — DEC-110. Spec só de API (nenhuma tela): o contrato de teste
 * ERC-1155 genérico emite `mintBatch(to, [2, 3], [amount, amount * 2])` em `local-env/seed/emit-events.ts`
 * (evento `TransferBatch(operator, from, to, ids[], values[])`).
 *
 * Config `TransferBatch` com `batch=true` criada ANTES da emissão → a projeção ao vivo credita o
 * destinatário em CADA id do lote (from = endereço zero não gera delta de débito). Assertivas por
 * DIFERENÇA (antes/depois), pra tolerar estado preexistente sem `E2E_RESET_DB=1`: o crédito do id 3 é
 * o dobro do crédito do id 2 (valores [amount, amount*2]).
 *
 * Também confere a validação contra a ABI: `batch=false` num evento cujos params são arrays => 400.
 */
import { test, expect } from '../fixtures/test'
import { config } from '../helpers/config'
import { loadDeployedContracts } from '../helpers/deployed'

const RECIPIENT = (process.env.E2E_RECIPIENT ?? '0x70997970c51812dc3a010c7d01b50e0d17dc79c8').toLowerCase()

test('TransferBatch com batch=true credita o destinatário em cada id do lote', async ({ evexApi }) => {
  const { TestERC1155 } = await loadDeployedContracts()
  const address = TestERC1155.address
  await evexApi.ensureContract(address, 'e2e balance ERC1155 batch')
  await evexApi.ensureAbi(address, JSON.stringify(TestERC1155.abi))

  // 1 única config TransferBatch por contrato (duplicar contaria em dobro numa rodada repetida)
  const existing = (await evexApi.getBalanceMonitors()).content.find(
    (m) => m.contractAddress.toLowerCase() === address.toLowerCase() && m.eventName === 'TransferBatch',
  )
  if (!existing) {
    const created = await evexApi.createBalanceMonitor({
      contractAddress: address,
      network: 'TESTNET',
      eventName: 'TransferBatch',
      behavior: 'TRANSFER',
      fromParam: 'from',
      toParam: 'to',
      valueParam: 'values',
      tokenIdParam: 'ids',
      batch: true,
    })
    expect(created.batch).toBe(true)
  } else {
    expect(existing.batch, 'config TransferBatch preexistente precisa ser batch=true').toBe(true)
  }

  const balanceOf = async (tokenId: string): Promise<bigint> => {
    const page = await evexApi.getBalances({ walletAddress: RECIPIENT, contractAddress: address, tokenId })
    return BigInt(page.content[0]?.balance ?? '0')
  }
  const before2 = await balanceOf('2')
  const before3 = await balanceOf('3')

  await evexApi.emitEvents(1)
  await evexApi.waitForDecodedEvent(address, 'TransferBatch')

  await evexApi.waitFor(
    () => balanceOf('3'),
    (b) => b > before3,
    { message: 'saldo do id 3 não aumentou após o mintBatch', timeoutMs: 60_000 },
  )
  const gain2 = (await balanceOf('2')) - before2
  const gain3 = (await balanceOf('3')) - before3
  expect(gain2 > 0n, 'o id 2 do lote também deve ter sido creditado').toBe(true)
  expect(gain3, 'values = [amount, amount * 2]').toBe(gain2 * 2n)
})

test('batch=false num evento com params array é rejeitado (400) pela validação contra a ABI', async ({ evexApi }) => {
  const { TestERC1155 } = await loadDeployedContracts()
  const address = TestERC1155.address
  await evexApi.ensureContract(address, 'e2e balance ERC1155 batch')
  await evexApi.ensureAbi(address, JSON.stringify(TestERC1155.abi))

  const res = await fetch(`${config.apiUrl}/v1/balance-monitors`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      contractAddress: address,
      network: 'TESTNET',
      eventName: 'TransferBatch',
      behavior: 'TRANSFER',
      fromParam: 'from',
      toParam: 'to',
      valueParam: 'values',
      tokenIdParam: 'ids',
      batch: false,
    }),
  })
  expect(res.status).toBe(400)
})
