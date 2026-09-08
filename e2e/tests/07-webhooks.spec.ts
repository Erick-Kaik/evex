/**
 * E15 · Fluxo 7 — Webhooks ponta a ponta (doc §4.6, E8).
 *
 * Usa o https://webhook.site como "endpoint de terceiro" real: cria um token, configura o webhook no
 * Evex apontando pra ele, emite um `Transfer`, e confere que (a) o webhook.site recebeu o `POST` com o
 * payload certo e (b) a tela de detalhe do webhook no Evex mostra a entrega como `DELIVERED`.
 *
 * E16: o formulário de webhook virou **guiado** por padrão (`<ContractAutocomplete>` + `<EventPicker>`,
 * rede herdada). O caminho feliz usa o modo guiado; a variação de filtro (evento que não existe no
 * catálogo) usa o modo **manual**, que aceita texto livre.
 *
 * Skipa (não falha) se não houver internet de saída pro webhook.site (`isReachable`).
 */
import { test, expect } from '../fixtures/test'
import { loadDeployedContracts } from '../helpers/deployed'
import * as webhookSite from '../helpers/webhook-site'

test.describe('webhooks via webhook.site', () => {
  test.beforeEach(async () => {
    test.skip(
      !(await webhookSite.isReachable()),
      'webhook.site inacessível (sem internet de saída?) — spec de webhook skipado',
    )
  })

  test('Transfer decodificado dispara o POST no endpoint configurado e a entrega vira DELIVERED (modo guiado)', async ({
    page,
    webhooksPage,
    evexApi,
  }) => {
    const { TestERC20 } = await loadDeployedContracts()
    const address = TestERC20.address
    const uuid = await webhookSite.createToken()
    const url = webhookSite.endpointUrl(uuid)

    try {
      // precondição: contrato + ABI (decodifica ao vivo na ingestão + alimenta o catálogo do <EventPicker>)
      await evexApi.ensureContract(address, 'e2e webhook ERC20')
      await evexApi.ensureAbi(address, JSON.stringify(TestERC20.abi))

      // --- cadastra o webhook PELA UI (modo guiado) ---
      await webhooksPage.open()
      await webhooksPage.goToNew()
      await webhooksPage.selectContract(address) // <ContractAutocomplete> — sem <NetworkSelect>
      await webhooksPage.pickEvent('Transfer') // <EventPicker> — catálogo do contrato
      await webhooksPage.fillUrl(url)
      await webhooksPage.submit()
      // E18: a submissão navega direto pro detalhe do webhook recém-criado — já devolve o id
      const webhookId = await webhooksPage.expectCreated()

      // o modo guiado herdou a rede do contrato
      const webhook = await evexApi.waitForWebhook(address, 'Transfer', url)
      expect(webhook.network).toBe('TESTNET')

      // --- emite um Transfer e espera decodificar ---
      await evexApi.emitEvents(1)
      await evexApi.waitForDecodedEvent(address, 'Transfer')

      // --- o webhook.site recebeu o POST do Evex com o payload do evento ---
      const received = await webhookSite.waitForRequest(
        uuid,
        (r) =>
          r.method === 'POST' &&
          r.content.includes('Transfer') &&
          r.content.toLowerCase().includes(address.toLowerCase()),
        { timeoutMs: 90_000 },
      )
      expect(received.json, 'payload do webhook deve ser JSON').toBeTruthy()

      // --- a entrega chega a DELIVERED (via API do Evex) ---
      await expect(async () => {
        const deliveries = await evexApi.apiGet<{ content: Array<{ status: string }> }>(
          `/v1/webhooks/${webhookId}/deliveries?page=0&size=20`,
        )
        expect(deliveries.content.some((d) => d.status === 'DELIVERED')).toBe(true)
      }).toPass({ timeout: 60_000, intervals: [2_000] })

      // --- e a tela de detalhe reflete o mesmo ---
      await page.reload()
      await expect(page.getByRole('heading', { name: 'Entregas' })).toBeVisible()
      await expect(page.getByText('Nenhuma entrega registrada ainda.')).toBeHidden()
      await expect(page.getByRole('cell', { name: 'DELIVERED' }).first()).toBeVisible({ timeout: 30_000 })
    } finally {
      await webhookSite.deleteToken(uuid)
    }
  })

  test('filtro: webhook de um evento que não é emitido nunca dispara (modo manual)', async ({
    page,
    webhooksPage,
    evexApi,
  }) => {
    const { TestEventEmitter } = await loadDeployedContracts()
    const address = TestEventEmitter.address
    const uuid = await webhookSite.createToken()

    try {
      await evexApi.ensureContract(address, 'e2e webhook filter')
      await evexApi.ensureAbi(address, JSON.stringify(TestEventEmitter.abi))

      // modo manual: "EventoQueNaoExiste" não está no catálogo — só o texto livre aceita
      await webhooksPage.open()
      await webhooksPage.goToNew()
      await webhooksPage.setFormMode('manual')
      await webhooksPage.fillContractManual(address)
      await webhooksPage.selectNetworkManual('TESTNET')
      await webhooksPage.fillEventManual('EventoQueNaoExiste')
      await webhooksPage.fillUrl(webhookSite.endpointUrl(uuid))
      await webhooksPage.submit()
      const webhookId = await webhooksPage.expectCreated()

      await evexApi.emitEvents(1)
      await evexApi.waitForRedecodeIdle()
      // Asserção negativa ("nada dispara"): não há condição pra esperar, só um settle curto e limitado
      // — única exceção deliberada à regra "sem waitForTimeout" (ver e2e/CLAUDE.md).
      await page.waitForTimeout(5_000)

      // determinístico: o Evex não registrou nenhuma entrega pra este webhook
      const deliveries = await evexApi.apiGet<{ content: unknown[] }>(
        `/v1/webhooks/${webhookId}/deliveries?page=0&size=20`,
      )
      expect(deliveries.content, 'o Evex não deve ter enfileirado entrega alguma').toHaveLength(0)
      // e o webhook.site também não recebeu nada
      const requests = await webhookSite.getRequests(uuid)
      expect(requests, 'nenhuma entrega esperada pra um evento que não é emitido').toHaveLength(0)
    } finally {
      await webhookSite.deleteToken(uuid)
    }
  })
})
