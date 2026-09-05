/**
 * Page Objects — centralizam os seletores da UI do Evex (labels em português, MUI). Seletores
 * acessíveis primeiro (`getByRole`/`getByLabel`/`getByText`); nenhum `data-testid` no frontend nesta
 * fase (ver e2e/CLAUDE.md e a nota da E15/E16 no roadmap — adicionar só se algum spec provar flaky).
 *
 * E16: os formulários de saldo e webhook ganharam 3 modos (guiado / visual / manual, DEC-072). O
 * default é o **guiado** (`<ContractAutocomplete>` + `<EventPicker>` + `<EventParamSelect>`), sem
 * `<NetworkSelect>` visível (a rede é herdada do contrato). Os helpers abaixo cobrem os 3 modos.
 */
import type { Page, Locator } from '@playwright/test'
import { expect } from '@playwright/test'

export type FormMode = 'guided' | 'visual' | 'manual'
const FORM_MODE_LABEL: Record<FormMode, string> = { guided: 'Guiado', visual: 'Visual', manual: 'Manual' }

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

// ---------- Helpers de interação com componentes MUI ----------

/**
 * O `<input>` de um MUI `Autocomplete`, escopado por `role="combobox"` + nome exato. **Não** usar
 * `getByLabel(nome)`: (1) o `<ul role="listbox">` do popup aberto compartilha o mesmo label via
 * `aria-labelledby` → `getByLabel` casa input + listbox (strict mode violation); (2) na tela de
 * Eventos, o `aria-label` do chip "sem ABI" ("…cadastre a ABI do contrato") casa por substring com
 * `getByLabel('Contrato')`. `role="combobox"` + `exact` evita os dois.
 */
function autocompleteInput(page: Page, name: string, nth?: 'first' | 'last'): Locator {
  const loc = page.getByRole('combobox', { name, exact: true })
  return nth === 'first' ? loc.first() : nth === 'last' ? loc.last() : loc
}

/** Campo de texto livre (`role="textbox"`) — idem, evita o substring do chip e o listbox. */
function textboxInput(page: Page, name: string, nth?: 'first' | 'last'): Locator {
  const loc = page.getByRole('textbox', { name, exact: true })
  return nth === 'first' ? loc.first() : nth === 'last' ? loc.last() : loc
}

/**
 * Escolhe uma opção num MUI `Autocomplete` cujo filtro por texto digitado **não** ajuda (ex.:
 * `<ContractAutocomplete>` filtra pelo nome do contrato, não pelo endereço): abre o popup e clica na
 * opção que contém `optionText` (trecho do endereço, nome, etc.). `combobox` = `autocompleteInput(...)`.
 */
async function pickAutocompleteByOptionText(page: Page, combobox: Locator, optionText: string): Promise<void> {
  await combobox.click()
  await page.getByRole('option').filter({ hasText: optionText }).first().click()
}

/**
 * Escolhe uma opção num MUI `Autocomplete` filtrável pelo texto digitado (ex.: `<EventPicker>`,
 * cujas opções são os próprios nomes de evento). `combobox` = `autocompleteInput(...)`.
 */
async function pickAutocompleteByTyping(page: Page, combobox: Locator, value: string): Promise<void> {
  await combobox.click()
  await combobox.fill(value)
  await page.getByRole('option', { name: value, exact: true }).first().click()
}

/** Escolhe uma opção num MUI `Select` (`<TextField select>`) pelo `role="combobox"` do rótulo. */
async function pickSelectOption(page: Page, comboboxName: string, optionName: string | RegExp): Promise<void> {
  await page.getByRole('combobox', { name: comboboxName }).click()
  const matcher = typeof optionName === 'string' ? { name: optionName, exact: true } : { name: optionName }
  await page.getByRole('option', matcher).first().click()
}

// ---------- Navegação ----------

export class AppNav {
  constructor(private readonly page: Page) {}

  async goToContracts() {
    await this.page.getByRole('tab', { name: 'Contratos' }).click()
    await expect(this.page.getByRole('heading', { name: 'Contratos monitorados' })).toBeVisible()
  }
  async goToEvents() {
    await this.page.getByRole('tab', { name: 'Eventos' }).click()
    await expect(this.page.getByRole('heading', { name: 'Eventos indexados' })).toBeVisible()
  }
  async goToBalances() {
    await this.page.getByRole('tab', { name: 'Saldos' }).click()
    await expect(this.page.getByRole('heading', { name: 'Saldos', exact: true })).toBeVisible()
  }
  async goToWebhooks() {
    await this.page.getByRole('tab', { name: 'Webhooks' }).click()
    await expect(this.page.getByRole('heading', { name: 'Webhooks' })).toBeVisible()
  }
}

// ---------- Contratos ----------

export class ContractsPage {
  constructor(readonly page: Page) {}

  async open() {
    await this.page.goto('/contracts')
    await expect(this.page.getByRole('heading', { name: 'Contratos monitorados' })).toBeVisible()
  }

  async openNewContractDialog(): Promise<ContractFormDialog> {
    await this.page.getByRole('button', { name: 'Novo contrato' }).click()
    const dialog = this.page.getByRole('dialog', { name: 'Novo contrato monitorado' })
    await expect(dialog).toBeVisible()
    return new ContractFormDialog(this.page, dialog)
  }

  row(address: string): Locator {
    return this.page.getByRole('row').filter({ hasText: address.slice(0, 10) })
  }

  async openContract(address: string) {
    await this.page.goto(`/contracts/${address}`)
    await expect(this.page.getByRole('heading', { level: 5 })).toBeVisible()
  }
}

export class ContractFormDialog {
  constructor(
    private readonly page: Page,
    private readonly root: Locator,
  ) {}

  async fill(opts: { address: string; name: string; network?: 'MAINNET' | 'TESTNET' }) {
    await this.root.getByLabel('Endereço').fill(opts.address)
    await this.root.getByLabel('Nome').fill(opts.name)
    await this.selectNetwork(opts.network ?? 'TESTNET')
  }

  async selectNetwork(network: 'MAINNET' | 'TESTNET') {
    await this.root.getByRole('combobox', { name: 'Rede' }).click()
    await this.page.getByRole('option', { name: network }).click()
  }

  /** Item da lista "Importar do Blockscout" — clicar preenche endereço+nome no form. */
  discoveryItem(nameOrAddress: string): Locator {
    return this.root.getByRole('button').filter({ hasText: nameOrAddress })
  }

  get discoveryUnavailable(): Locator {
    return this.root.getByText('Discovery via Blockscout não está disponível')
  }

  async submit() {
    await this.root.getByRole('button', { name: 'Cadastrar', exact: true }).click()
  }
}

export class ContractDetailPage {
  constructor(readonly page: Page) {}

  async openAbiRegisterDialog(): Promise<AbiRegisterDialog> {
    await this.page.getByRole('button', { name: 'Cadastrar nova versão' }).click()
    const dialog = this.page.getByRole('dialog', { name: 'Cadastrar nova versão de ABI' })
    await expect(dialog).toBeVisible()
    return new AbiRegisterDialog(this.page, dialog)
  }

  abiVersionRow(source: string): Locator {
    return this.page.getByRole('row').filter({ hasText: source })
  }

  /**
   * Indicador de redecode ao vivo (E17/DEC-078): `<CircularProgress>` + "Decodificando o histórico…"
   * empurrado via SSE enquanto um redecode global roda. Some sozinho quando `running` volta a `false`.
   */
  get redecodeIndicator(): Locator {
    return this.page.getByText('Decodificando o histórico', { exact: false })
  }
}

export class AbiRegisterDialog {
  constructor(
    private readonly page: Page,
    private readonly root: Locator,
  ) {}

  async chooseSource(label: 'Colar texto' | 'Enviar arquivo (.abi)' | 'Buscar no Blockscout' | 'Detectar automaticamente') {
    await this.root.getByRole('radio', { name: label }).check()
  }

  async pasteAbi(json: string) {
    await this.chooseSource('Colar texto')
    await this.root.getByLabel('Conteúdo do ABI (JSON)').fill(json)
  }

  async detectInterfaces() {
    await this.chooseSource('Detectar automaticamente')
    await this.root.getByRole('button', { name: 'Detectar interfaces' }).click()
  }

  suggestion(templateName: string): Locator {
    return this.root.getByText(templateName)
  }

  async submit() {
    await this.root.getByRole('button', { name: 'Cadastrar versão de ABI' }).click()
    await expect(this.root).toBeHidden()
  }
}

// ---------- Eventos ----------

export class EventsPage {
  constructor(readonly page: Page) {}

  async open() {
    await this.page.goto('/events')
    await expect(this.page.getByRole('heading', { name: 'Eventos indexados' })).toBeVisible()
  }

  /** Escolhe um contrato **monitorado** no `<ContractAutocomplete>` (modo padrão do filtro). */
  async selectContract(address: string) {
    await pickAutocompleteByOptionText(this.page, autocompleteInput(this.page, 'Contrato'), address.slice(0, 10).toLowerCase())
  }

  /** Revela o campo de endereço livre (`0x…`) — pra filtrar um contrato fora da lista de monitorados. */
  async useManualAddress() {
    await this.page.getByRole('button', { name: 'digitar endereço manualmente' }).click()
  }

  async fillManualAddress(address: string) {
    await textboxInput(this.page, 'Contrato').fill(address)
  }

  /** Nome do evento via `<EventPicker>` (catálogo) — só quando há contrato monitorado selecionado. */
  async pickEvent(name: string) {
    await pickAutocompleteByTyping(this.page, autocompleteInput(this.page, 'Nome do evento'), name)
  }

  /** Nome do evento como texto livre (modo de endereço manual — sem catálogo). */
  async fillEventText(name: string) {
    await textboxInput(this.page, 'Nome do evento').fill(name)
  }

  async submitFilter() {
    await this.page.getByRole('button', { name: 'Filtrar' }).click()
  }

  // --- compatibilidade / atalhos ---
  async filterByContract(address: string) {
    await this.selectContract(address)
    await this.submitFilter()
  }

  /** Linha da tabela de eventos que contém o texto dado (nome do evento, "sem ABI", etc.). */
  rowWith(text: string): Locator {
    return this.page.getByRole('row').filter({ hasText: text })
  }

  /** Chip "sem ABI" (evento indexado ainda não decodificado — era o rótulo "raw" antes da E16). */
  get noAbiChip(): Locator {
    return this.page.getByRole('row').getByText('sem ABI', { exact: true })
  }

  get emptyState(): Locator {
    return this.page.getByText('Nenhum evento encontrado com esses filtros.')
  }

  /** Linhas de dados da tabela de eventos (exclui o cabeçalho). */
  get eventRows(): Locator {
    return this.page.locator('tbody tr')
  }

  /** Link na coluna "Contrato" (contrato monitorado → nome clicável pro detalhe). */
  contractLink(name: string): Locator {
    return this.page.getByRole('row').getByRole('link', { name })
  }

  /** Célula da coluna "Contrato" com o endereço encurtado (contrato não monitorado). */
  shortAddressCell(address: string): Locator {
    return this.page.getByText(`${address.slice(0, 8)}…${address.slice(-4)}`)
  }
}

// ---------- Saldos ----------

export class BalancesPage {
  constructor(readonly page: Page) {}

  async open() {
    await this.page.goto('/balances')
    await expect(this.page.getByRole('heading', { name: 'Saldos', exact: true })).toBeVisible()
  }

  // ----- Consulta de saldos (primeira ocorrência dos campos = seção de consulta) -----
  async query(opts: { wallet?: string; contract?: string; contractManual?: boolean }) {
    const p = this.page
    if (opts.wallet) await textboxInput(p, 'Carteira', 'first').fill(opts.wallet)
    if (opts.contract) {
      if (opts.contractManual) {
        await p.getByRole('button', { name: 'digitar endereço manualmente' }).first().click()
        await textboxInput(p, 'Contrato', 'first').fill(opts.contract)
      } else {
        await pickAutocompleteByOptionText(
          p,
          autocompleteInput(p, 'Contrato', 'first'),
          opts.contract.slice(0, 10).toLowerCase(),
        )
      }
    }
    await p.getByRole('button', { name: 'Consultar' }).click()
  }

  balanceRow(wallet: string): Locator {
    return this.page.getByRole('row').filter({ hasText: wallet.slice(0, 10) })
  }

  // ----- Configuração de monitoramento -----
  get monitorSection(): Locator {
    return this.page.getByRole('region').filter({ hasText: 'Configurações de monitoramento' })
  }

  /** Alterna o modo do formulário "Nova configuração" (Guiado / Visual / Manual). */
  async setMonitorFormMode(mode: FormMode) {
    await this.page.getByRole('button', { name: FORM_MODE_LABEL[mode], exact: true }).click()
  }

  /** Confere qual modo o `<FormModeToggle>` está exibindo como selecionado (pra testar persistência). */
  async expectMonitorFormMode(mode: FormMode) {
    await expect(this.page.getByRole('button', { name: FORM_MODE_LABEL[mode], exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  }

  /** `<ContractAutocomplete>` da seção de monitoramento (2ª ocorrência de "Contrato" na página). */
  async selectMonitorContract(address: string) {
    await pickAutocompleteByOptionText(
      this.page,
      autocompleteInput(this.page, 'Contrato', 'last'),
      address.slice(0, 10).toLowerCase(),
    )
  }

  async pickMonitorEvent(eventName: string) {
    await pickAutocompleteByTyping(this.page, autocompleteInput(this.page, 'Nome do evento'), eventName)
  }

  async setBehavior(behavior: 'CREDIT' | 'DEBIT' | 'DELTA' | 'TRANSFER') {
    await pickSelectOption(this.page, 'Comportamento', behavior)
  }

  /** `<EventParamSelect>` do modo guiado — escolhe o parâmetro pelo nome (opção "nome (tipo)"). */
  async selectParam(label: string, paramName: string) {
    await pickSelectOption(this.page, label, new RegExp(`^${escapeRegExp(paramName)} \\(`))
  }

  /**
   * `<ParamBindingCanvas>` (modo visual): seleciona o chip do parâmetro e clica no slot compatível.
   * `paramType` é o tipo Solidity exato (o chip tem `aria-label` "Parâmetro <nome>, tipo <tipo>").
   */
  async bindParamOnCanvas(param: { name: string; type: string }, slotLabel: string) {
    await this.page.getByRole('button', { name: `Parâmetro ${param.name}, tipo ${param.type}` }).click()
    await this.page.getByRole('button', { name: `Campo ${slotLabel}` }).click()
  }

  canvasSlot(slotLabel: string): Locator {
    return this.page.getByRole('button', { name: `Campo ${slotLabel}` })
  }

  get canvasTypeMismatch(): Locator {
    return this.page.getByText('Tipo incompatível', { exact: false })
  }

  async submitMonitor() {
    await this.page.getByRole('button', { name: 'Cadastrar', exact: true }).click()
    await expect(this.page.getByText('Configuração cadastrada com sucesso.')).toBeVisible()
  }

  /**
   * Cria uma config de monitoramento pela UI. `mode='guided'` (default) usa autocomplete/selects;
   * `mode='manual'` reproduz o caminho histórico (texto livre + `<NetworkSelect>`).
   */
  async createMonitor(opts: {
    contract: string
    eventName: string
    behavior: 'CREDIT' | 'DEBIT' | 'DELTA' | 'TRANSFER'
    walletParam?: string
    fromParam?: string
    toParam?: string
    valueParam: string
    tokenIdParam?: string
    mode?: FormMode
  }) {
    const p = this.page
    const mode: FormMode = opts.mode ?? 'guided'
    await this.setMonitorFormMode(mode)

    if (mode === 'manual') {
      await textboxInput(p, 'Contrato', 'last').fill(opts.contract)
      await p.getByRole('combobox', { name: 'Rede' }).last().click()
      await p.getByRole('option', { name: 'TESTNET' }).click()
      await textboxInput(p, 'Nome do evento').fill(opts.eventName)
      await this.setBehavior(opts.behavior)
      if (opts.behavior === 'TRANSFER') {
        await p.getByLabel('Parâmetro de origem (from)').fill(opts.fromParam ?? 'from')
        await p.getByLabel('Parâmetro de destino (to)').fill(opts.toParam ?? 'to')
      } else {
        await p.getByLabel('Parâmetro da carteira').fill(opts.walletParam ?? 'to')
      }
      await p.getByLabel('Parâmetro do montante/delta').fill(opts.valueParam)
      if (opts.tokenIdParam) await p.getByLabel('Parâmetro do tokenId (opcional)').fill(opts.tokenIdParam)
      await this.submitMonitor()
      return
    }

    // guided
    await this.selectMonitorContract(opts.contract)
    await this.pickMonitorEvent(opts.eventName)
    await this.setBehavior(opts.behavior)
    if (opts.behavior === 'TRANSFER') {
      await this.selectParam('Parâmetro de origem (from)', opts.fromParam ?? 'from')
      await this.selectParam('Parâmetro de destino (to)', opts.toParam ?? 'to')
    } else {
      await this.selectParam('Parâmetro da carteira', opts.walletParam ?? 'to')
    }
    await this.selectParam('Parâmetro do montante/delta', opts.valueParam)
    if (opts.tokenIdParam) await this.selectParam('Parâmetro do tokenId (opcional)', opts.tokenIdParam)
    await this.submitMonitor()
  }

  monitorRow(contract: string): Locator {
    return this.page.getByRole('row').filter({ hasText: contract.slice(0, 10) })
  }

  async triggerBackfill(contract: string) {
    await this.monitorRow(contract).getByRole('button', { name: 'Reprocessar histórico' }).click()
  }
}

// ---------- Webhooks ----------

export class WebhooksPage {
  constructor(readonly page: Page) {}

  async open() {
    await this.page.goto('/webhooks')
    await expect(this.page.getByRole('heading', { name: 'Webhooks' })).toBeVisible()
  }

  async setFormMode(mode: FormMode) {
    await this.page.getByRole('button', { name: FORM_MODE_LABEL[mode], exact: true }).click()
  }

  async expectFormMode(mode: FormMode) {
    await expect(this.page.getByRole('button', { name: FORM_MODE_LABEL[mode], exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  }

  // --- modo guiado / visual (contrato monitorado) ---
  async selectContract(address: string) {
    await pickAutocompleteByOptionText(this.page, autocompleteInput(this.page, 'Contrato'), address.slice(0, 10).toLowerCase())
  }
  async pickEvent(eventName: string) {
    await pickAutocompleteByTyping(this.page, autocompleteInput(this.page, 'Nome do evento'), eventName)
  }

  // --- modo manual (texto livre) ---
  async fillContractManual(address: string) {
    await textboxInput(this.page, 'Contrato').fill(address)
  }
  async selectNetworkManual(network: 'MAINNET' | 'TESTNET') {
    await this.page.getByRole('combobox', { name: 'Rede' }).click()
    await this.page.getByRole('option', { name: network }).click()
  }
  async fillEventManual(eventName: string) {
    await textboxInput(this.page, 'Nome do evento').fill(eventName)
  }

  async fillUrl(url: string) {
    await this.page.getByLabel('URL de destino').fill(url)
  }

  async addFilter() {
    await this.page.getByRole('button', { name: 'adicionar filtro' }).click()
  }

  /** Modo guiado: escolhe o parâmetro do filter `index` (0-based) no `<EventParamSelect kind="any">`. */
  async selectFilterParam(paramName: string) {
    await pickSelectOption(this.page, 'parâmetro', new RegExp(`^${escapeRegExp(paramName)} \\(`))
  }

  /** Modo visual: vincula um chip de parâmetro ao slot de filtro pelo canvas. */
  async bindFilterOnCanvas(param: { name: string; type: string }, slotLabel = 'Filtro 1') {
    await this.page.getByRole('button', { name: `Parâmetro ${param.name}, tipo ${param.type}` }).click()
    await this.page.getByRole('button', { name: `Campo ${slotLabel}` }).click()
  }

  async fillFilterValue(value: string) {
    await this.page.getByLabel('valor').fill(value)
  }

  /** `<PayloadTemplateBuilder>` (modo visual): insere um token no template por clique. */
  async insertPayloadToken(token: string) {
    await this.page.getByRole('button', { name: `Inserir ${token}` }).click()
  }

  get payloadField(): Locator {
    return this.page.getByLabel('Template montado')
  }

  async submit() {
    await this.page.getByRole('button', { name: 'Cadastrar', exact: true }).click()
  }

  async expectCreated() {
    await expect(this.page.getByText('Webhook cadastrado com sucesso.')).toBeVisible()
  }

  row(address: string): Locator {
    return this.page.getByRole('row').filter({ hasText: address.slice(0, 10) })
  }

  /** Abre o detalhe do webhook clicando na linha e devolve o id extraído da URL. */
  async openDetail(address: string): Promise<string> {
    await this.row(address).first().click()
    await expect(this.page).toHaveURL(/\/webhooks\/[0-9a-f-]{36}/i)
    return this.page.url().split('/webhooks/')[1]!.split(/[/?#]/)[0]!
  }
}

// ---------- Demonstração (E17/DEC-075 — aba de ambiente local) ----------

export class DemoPage {
  constructor(readonly page: Page) {}

  /** Aba na navegação — só existe quando `VITE_LOCALENV_CONTROL_URL` está setada e o health respondeu. */
  get tab(): Locator {
    return this.page.getByRole('tab', { name: 'Demonstração' })
  }

  async open() {
    await this.tab.click()
    await expect(this.page.getByRole('heading', { name: 'Demonstração' })).toBeVisible()
  }

  /** `true` se a aba "Demonstração" está presente (frontend servido com a env var + control-panel no ar). */
  async isAvailable(): Promise<boolean> {
    await this.page.goto('/')
    return this.tab
      .waitFor({ state: 'visible', timeout: 8_000 })
      .then(() => true)
      .catch(() => false)
  }

  /** Card de uma ação — o `.MuiCard-root` que contém o título da ação (MUI `Card` não tem role). */
  card(label: string): Locator {
    return this.page
      .locator('.MuiCard-root')
      .filter({ has: this.page.getByRole('heading', { name: label }) })
  }

  /** O `<pre>` que exibe o comando exato ANTES de rodar (DEC-075). */
  commandText(label: string): Locator {
    return this.card(label).locator('pre').first()
  }

  async run(label: string) {
    await this.card(label).getByRole('button', { name: /Executar/ }).click()
  }

  async setParam(label: string, paramLabel: string, value: number) {
    await this.card(label).getByLabel(paramLabel).fill(String(value))
  }

  statusChip(label: string, text: string | RegExp): Locator {
    return this.card(label).locator('.MuiChip-root').filter({ hasText: text })
  }

  liveOutput(label: string): Locator {
    return this.card(label).locator('pre').last()
  }

  errorAlert(label: string): Locator {
    return this.card(label).getByRole('alert')
  }
}
