/**
 * E15 · Fluxo 4 — Indexação de eventos + redecode de histórico já gravado (doc §4.2/§4.3, E4/E5).
 *
 * O cenário central: o poller ingere eventos como "raw" enquanto não há ABI; ao cadastrar a ABI, o
 * backend dispara o redecode e os eventos JÁ GRAVADOS passam a decodificados — visível na tela de
 * Eventos (chip "sem ABI" → nome do evento + parâmetros).
 *
 * Usa o `TestEventEmitter` de propósito: nenhum spec de número menor cadastra a ABI dele (o
 * `02-discovery` e o `05` mexem com `TestERC20`; o `06` com `TestERC1155`), então o estado "contrato
 * sem ABI ainda" se sustenta aqui mesmo com a suíte rodando em série (DEC-070 §4).
 *
 * E16: o filtro da tela de Eventos passou a ter `<ContractAutocomplete>` (contrato monitorado) +
 * `<EventPicker>` (catálogo), coluna "Contrato" na tabela, e um fallback "digitar endereço
 * manualmente" pra filtrar contratos fora da lista de monitorados. Este spec exercita os dois.
 */
import { test, expect } from '../fixtures/test'
import { loadDeployedContracts } from '../helpers/deployed'

test('evento raw vira decodificado depois do cadastro da ABI (redecode do histórico)', async ({
  page,
  contractsPage,
  contractDetailPage,
  eventsPage,
  evexApi,
}) => {
  const { TestEventEmitter } = await loadDeployedContracts()
  const address = TestEventEmitter.address
  const abiJson = JSON.stringify(TestEventEmitter.abi)

  // Este fluxo exige o TestEventEmitter SEM ABI (o teste 2 abaixo e os specs 07/08 cadastram a dele).
  // Numa rodada em série sem estado limpo, ele já foi cadastrado antes → skipa (rode com --clean /
  // E2E_RESET_DB=1 + backend recém-iniciado pra exercitar o caminho completo). Mesmo espírito do 06.
  test.skip(
    await evexApi.contractHasAbi(address),
    'TestEventEmitter já tem ABI nesta stack — o cenário raw→redecode precisa do contrato sem ABI (rode com --clean)',
  )

  // --- precondição: contrato conhecido SEM ABI + eventos gerados e ingeridos como raw ---
  await evexApi.ensureContract(address, 'e2e redecode EventEmitter')
  await evexApi.emitEvents(2)
  const firstEvent = await evexApi.waitForAnyEvent(address)
  expect(firstEvent.decoded, 'sem ABI, o evento deve estar raw').toBe(false)

  // --- na tela de Eventos, o contrato (monitorado) aparece com evento(s) "sem ABI" ---
  await eventsPage.open()
  await eventsPage.filterByContract(address) // <ContractAutocomplete> — contrato monitorado, sem ABI
  await expect(eventsPage.noAbiChip.first()).toBeVisible()

  // --- cadastra a ABI pela UI → backend dispara redecode ---
  await contractsPage.openContract(address)
  const abiDialog = await contractDetailPage.openAbiRegisterDialog()
  await abiDialog.pasteAbi(abiJson)
  await abiDialog.submit()

  // --- espera o redecode terminar e decodificar o WidgetCreated histórico ---
  await evexApi.waitForRedecodeIdle()
  const decoded = await evexApi.waitForDecodedEvent(address, 'WidgetCreated')
  expect(decoded.decodedParameters).toBeTruthy()

  // --- na tela de Eventos, os mesmos eventos agora aparecem decodificados ---
  await eventsPage.open()
  await eventsPage.filterByContract(address)
  const widgetRow = eventsPage.rowWith('WidgetCreated').first()
  await expect(widgetRow).toBeVisible()

  // coluna "Contrato": contrato monitorado → link clicável pro detalhe (não endereço cru)
  await expect(widgetRow.getByRole('link').first()).toBeVisible()

  await widgetRow.getByRole('button', { name: 'ver parâmetros' }).click()
  // os parâmetros decodificados do WidgetCreated aparecem (id / creator / name / value)
  await expect(page.getByRole('cell', { name: 'value', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'creator', exact: true })).toBeVisible()
})

test('filtros da tela de Eventos (contrato monitorado + nome do evento pelo catálogo) — modo guiado', async ({
  eventsPage,
  evexApi,
}) => {
  const { TestEventEmitter } = await loadDeployedContracts()
  await evexApi.ensureContract(TestEventEmitter.address, 'e2e events filter')
  await evexApi.ensureAbi(TestEventEmitter.address, JSON.stringify(TestEventEmitter.abi))
  await evexApi.emitEvents(1)
  await evexApi.waitForRedecodeIdle()
  await evexApi.waitForDecodedEvent(TestEventEmitter.address, 'WidgetCreated')

  // filtro por contrato (autocomplete): traz eventos, some o estado vazio
  await eventsPage.open()
  await eventsPage.selectContract(TestEventEmitter.address)
  await eventsPage.submitFilter()
  await expect(eventsPage.emptyState).toBeHidden()
  await expect(eventsPage.rowWith('WidgetCreated').first()).toBeVisible()

  // filtro adicional por nome do evento via <EventPicker> (catálogo do contrato): só aquele evento
  await eventsPage.pickEvent('WidgetCreated')
  await eventsPage.submitFilter()
  await expect(eventsPage.rowWith('WidgetCreated').first()).toBeVisible()
  await expect(eventsPage.rowWith('BatchProcessed')).toHaveCount(0)
})

test('fallback: filtra eventos por endereço livre ("digitar endereço manualmente")', async ({
  eventsPage,
  evexApi,
}) => {
  // TestERC721 só é registrado no `05` — neste ponto da suíte serial (sem reset) ele em geral ainda
  // não é monitorado; o `emit-events` já gerou Transfers dele (safeMint). O que este teste prova é
  // que o escape hatch de endereço livre funciona (não depende de o contrato estar na lista).
  const { TestERC721 } = await loadDeployedContracts()
  const address = TestERC721.address
  await evexApi.emitEvents(1)
  await evexApi.waitForAnyEvent(address)

  await eventsPage.open()
  await eventsPage.useManualAddress() // revela o TextField "0x…"
  await eventsPage.fillManualAddress(address)
  // sem contrato monitorado selecionado, o nome do evento volta a ser texto livre (sem catálogo)
  await expect(eventsPage.page.getByRole('textbox', { name: 'Nome do evento' })).toBeVisible()
  await eventsPage.submitFilter()

  await expect(eventsPage.emptyState).toBeHidden()
  await expect(eventsPage.eventRows.first()).toBeVisible()
})
