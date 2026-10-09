import { beforeEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AbiHistoryItem, AbiHistoryPlan } from '../../api/api'
import { renderWithProviders } from '../../test/renderWithProviders'
import { AbiHistoryImportDialog } from './AbiHistoryImportDialog'

const { getContractAbiHistoryPlan, importContractAbiHistory } = vi.hoisted(() => ({
  getContractAbiHistoryPlan: vi.fn(),
  importContractAbiHistory: vi.fn(),
}))

vi.mock('../../api/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/api')>()
  class ContractsApi {
    getContractAbiHistoryPlan = getContractAbiHistoryPlan
    importContractAbiHistory = importContractAbiHistory
  }
  return { ...actual, ContractsApi }
})

const ADDRESS = '0x00000000000000000000000000000000000000f0'
const IMPL = '0x44440000000000000000000000000000000000a1'

function item(overrides: Partial<AbiHistoryItem> & { sequence: number }): AbiHistoryItem {
  return {
    validFromBlock: 0,
    implementationAddress: IMPL,
    upgradeSource: 'BOTH',
    abiAvailable: true,
    status: 'APPLY',
    identicalToPrevious: false,
    ...overrides,
  }
}

function plan(items: AbiHistoryItem[], overrides: Partial<AbiHistoryPlan> = {}): AbiHistoryPlan {
  return {
    contractAddress: ADDRESS,
    historySource: 'AUTO',
    ingestedUntilBlock: 620,
    truncated: false,
    fingerprint: 'fp-1',
    warnings: [],
    items,
    ...overrides,
  }
}

const APPLY_PLAN = plan([
  item({ sequence: 1, validFromBlock: 0, validToBlock: 299, upgradeBlockNumber: 100, txHash: '0xabc' }),
  item({
    sequence: 2,
    validFromBlock: 300,
    validToBlock: 499,
    upgradeBlockNumber: 300,
    diffFromPrevious: {
      eventsAdded: ['EventTwo(uint256)'],
      eventsRemoved: [],
      methodsAdded: ['burn(uint256)', 'mint(address,uint256)', 'pause()'],
      methodsRemoved: [],
    },
  }),
  item({ sequence: 3, validFromBlock: 500, identicalToPrevious: true, status: 'SKIP_IDENTICAL' }),
])

const PENDING_PLAN = plan([
  item({ sequence: 1, status: 'SKIP_ALREADY_IMPORTED', validToBlock: 299 }),
  item({ sequence: 2, validFromBlock: 300, abiAvailable: false, status: 'PENDING_NO_VERIFIED_ABI' }),
  item({ sequence: 3, validFromBlock: 500, status: 'BLOCKED_AFTER_PENDING' }),
])

function renderDialog(extra: { onRegisterManual?: () => void } = {}) {
  return renderWithProviders(<AbiHistoryImportDialog address={ADDRESS} open onClose={vi.fn()} {...extra} />)
}

beforeEach(() => {
  getContractAbiHistoryPlan.mockReset()
  importContractAbiHistory.mockReset()
})

describe('AbiHistoryImportDialog', () => {
  it('renderiza o plano com cabeçalho, faixa aberta e diff expansível', async () => {
    getContractAbiHistoryPlan.mockResolvedValue({ data: APPLY_PLAN })
    renderDialog()

    expect(await screen.findByText('0 → 299')).toBeInTheDocument()
    expect(screen.getByText('500 → aberto')).toBeInTheDocument()
    expect(screen.getByText('620')).toBeInTheDocument()
    expect(getContractAbiHistoryPlan).toHaveBeenCalledWith(ADDRESS, 'AUTO', false)
    expect(screen.queryByText('mint(address,uint256)')).not.toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'ver diferenças do item 2' }))
    expect(await screen.findByText('mint(address,uint256)')).toBeInTheDocument()
    expect(screen.getByText('pause()')).toBeInTheDocument()
    expect(screen.getByText('idêntica à anterior')).toBeInTheDocument()
  })

  it('mostra status desconhecido como "não aplicável"', async () => {
    getContractAbiHistoryPlan.mockResolvedValue({ data: plan([item({ sequence: 1, status: 'NEW_THING' })]) })
    renderDialog()

    expect(await screen.findByText('não aplicável')).toBeInTheDocument()
  })

  it('PENDING/BLOCKED: instrução de cadastro manual, link para o cadastro e apply desabilitado', async () => {
    getContractAbiHistoryPlan.mockResolvedValue({ data: PENDING_PLAN })
    const onRegisterManual = vi.fn()
    renderDialog({ onRegisterManual })

    expect(await screen.findByText('Implementação sem ABI verificada')).toBeInTheDocument()
    expect(screen.getByText('PENDING_NO_VERIFIED_ABI')).toBeInTheDocument()
    expect(screen.getByText('BLOCKED_AFTER_PENDING')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Aplicar importação/ })).toBeDisabled()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Cadastrar ABI manualmente' }))
    expect(onRegisterManual).toHaveBeenCalled()
  })

  it('troca de fonte refaz o dry-run com a fonte escolhida', async () => {
    getContractAbiHistoryPlan.mockResolvedValue({ data: APPLY_PLAN })
    renderDialog()
    const user = userEvent.setup()
    await screen.findByText('0 → 299')

    await user.click(screen.getByRole('combobox', { name: 'Fonte do histórico' }))
    await user.click(screen.getByRole('option', { name: 'Só Blockscout' }))

    await waitFor(() => expect(getContractAbiHistoryPlan).toHaveBeenCalledWith(ADDRESS, 'BLOCKSCOUT_ONLY', false))
  })

  it('confirma e envia o fingerprint do plano; sem opt-in, replaceBlockscoutLegacy=false; resultado e invalidações', async () => {
    getContractAbiHistoryPlan.mockResolvedValue({ data: APPLY_PLAN })
    importContractAbiHistory.mockResolvedValue({
      data: { ...APPLY_PLAN, appliedCount: 2, redecodeStarted: false },
    })
    const { queryClient } = renderDialog()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const user = userEvent.setup()

    await screen.findByText('0 → 299')
    await user.click(screen.getByRole('button', { name: /Aplicar importação/ }))

    const confirm = await screen.findByRole('dialog', { name: 'Confirmar importação' })
    expect(within(confirm).queryByRole('checkbox')).not.toBeInTheDocument()
    expect(importContractAbiHistory).not.toHaveBeenCalled()
    await user.click(within(confirm).getByRole('button', { name: 'Confirmar importação' }))

    await waitFor(() =>
      expect(importContractAbiHistory).toHaveBeenCalledWith(ADDRESS, {
        historySource: 'AUTO',
        expectedFingerprint: 'fp-1',
        replaceBlockscoutLegacy: false,
      }),
    )
    expect(getContractAbiHistoryPlan).not.toHaveBeenCalledWith(ADDRESS, 'AUTO', true)
    expect(await screen.findByText('Importação concluída')).toBeInTheDocument()
    expect(screen.getByText(/2 versão\(ões\) de ABI cadastrada/)).toBeInTheDocument()
    expect(screen.getByText(/Já havia um redecode em curso/)).toBeInTheDocument()

    const keys = invalidate.mock.calls.map((call) => JSON.stringify(call[0]?.queryKey))
    expect(keys).toEqual(
      expect.arrayContaining([
        JSON.stringify(['contract-abi-versions', ADDRESS]),
        JSON.stringify(['contract-abi-entries', ADDRESS]),
        JSON.stringify(['events']),
        JSON.stringify(['contract-abi-history-plan', ADDRESS]),
      ]),
    )
  })

  describe('opt-in "Substituir versões legadas" (cabeçalho)', () => {
    const LEGACY_ID = 'abcdef12-0000-4000-8000-000000000001'
    // Sem a flag o legado vira conflito (apply desabilitado); com a flag o plano tem APPLY.
    const CONFLICT_PLAN = plan([
      item({ sequence: 1, status: 'CONFLICT_MANUAL' }),
      item({ sequence: 2, validFromBlock: 300, status: 'BLOCKED_AFTER_PENDING' }),
    ])
    const FLAG_PLAN = plan(
      [item({ sequence: 1 }), item({ sequence: 2, validFromBlock: 300 })],
      { fingerprint: 'fp-flag', legacyVersionsToReplace: [{ abiId: LEGACY_ID, validFromBlock: 0 }] },
    )

    function mockByFlag() {
      getContractAbiHistoryPlan.mockImplementation(
        async (_address: string, _source: string, replace: boolean) => ({ data: replace ? FLAG_PLAN : CONFLICT_PLAN }),
      )
    }

    it('o checkbox fica no diálogo principal, refaz o GET com a flag e habilita o apply', async () => {
      mockByFlag()
      renderDialog()
      const user = userEvent.setup()

      await screen.findByText('CONFLICT_MANUAL')
      expect(screen.getByRole('button', { name: /Aplicar importação/ })).toBeDisabled()

      await user.click(screen.getByRole('checkbox', { name: /Substituir versões legadas/ }))

      await waitFor(() => expect(getContractAbiHistoryPlan).toHaveBeenCalledWith(ADDRESS, 'AUTO', true))
      await waitFor(() => expect(screen.getByRole('button', { name: 'Aplicar importação (2)' })).toBeEnabled())
    })

    it('alerta lista as versões legadas a apagar (abiId curto + bloco)', async () => {
      mockByFlag()
      renderDialog()
      await screen.findByText('CONFLICT_MANUAL')
      expect(screen.queryByText(/legada\(s\) será\(ão\) apagada/)).not.toBeInTheDocument()

      await userEvent.setup().click(screen.getByRole('checkbox', { name: /Substituir versões legadas/ }))

      expect(await screen.findByText('1 versão(ões) legada(s) será(ão) apagada(s)')).toBeInTheDocument()
      expect(screen.getByText('abcdef12')).toBeInTheDocument()
    })

    it('apply envia a flag e o fingerprint do plano exibido (com a flag)', async () => {
      mockByFlag()
      importContractAbiHistory.mockResolvedValue({ data: { ...FLAG_PLAN, appliedCount: 2, redecodeStarted: true } })
      renderDialog()
      const user = userEvent.setup()

      await screen.findByText('CONFLICT_MANUAL')
      await user.click(screen.getByRole('checkbox', { name: /Substituir versões legadas/ }))
      await user.click(await screen.findByRole('button', { name: 'Aplicar importação (2)' }))
      const confirm = await screen.findByRole('dialog', { name: 'Confirmar importação' })
      expect(within(confirm).getByText(/1 versão\(ões\) legada\(s\) importada\(s\) do Blockscout/)).toBeInTheDocument()
      await user.click(within(confirm).getByRole('button', { name: 'Confirmar importação' }))

      await waitFor(() =>
        expect(importContractAbiHistory).toHaveBeenCalledWith(ADDRESS, {
          historySource: 'AUTO',
          expectedFingerprint: 'fp-flag',
          replaceBlockscoutLegacy: true,
        }),
      )
    })

    it('409 refaz o plano mantendo a flag', async () => {
      mockByFlag()
      importContractAbiHistory.mockRejectedValue(
        Object.assign(new Error('conflict'), {
          isAxiosError: true,
          response: { status: 409, data: { code: 'ABI_HISTORY_PLAN_CHANGED', message: 'mudou' } },
        }),
      )
      renderDialog()
      const user = userEvent.setup()

      await screen.findByText('CONFLICT_MANUAL')
      await user.click(screen.getByRole('checkbox', { name: /Substituir versões legadas/ }))
      await user.click(await screen.findByRole('button', { name: 'Aplicar importação (2)' }))
      const confirm = await screen.findByRole('dialog', { name: 'Confirmar importação' })
      getContractAbiHistoryPlan.mockClear()
      await user.click(within(confirm).getByRole('button', { name: 'Confirmar importação' }))

      expect(await screen.findByText(/O plano mudou desde o dry-run; revise de novo/)).toBeInTheDocument()
      await waitFor(() => expect(getContractAbiHistoryPlan).toHaveBeenCalledWith(ADDRESS, 'AUTO', true))
      expect(getContractAbiHistoryPlan).not.toHaveBeenCalledWith(ADDRESS, 'AUTO', false)
      await waitFor(() => expect(screen.getByRole('checkbox', { name: /Substituir versões legadas/ })).toBeChecked())
    })
  })

  it('409 ABI_HISTORY_PLAN_CHANGED: avisa para revisar e recarrega o plano', async () => {
    getContractAbiHistoryPlan.mockResolvedValue({ data: APPLY_PLAN })
    importContractAbiHistory.mockRejectedValue(
      Object.assign(new Error('conflict'), {
        isAxiosError: true,
        response: { status: 409, data: { code: 'ABI_HISTORY_PLAN_CHANGED', message: 'mudou' } },
      }),
    )
    renderDialog()
    const user = userEvent.setup()

    await screen.findByText('0 → 299')
    await user.click(screen.getByRole('button', { name: /Aplicar importação/ }))
    const confirm = await screen.findByRole('dialog', { name: 'Confirmar importação' })
    await user.click(within(confirm).getByRole('button', { name: 'Confirmar importação' }))

    expect(await screen.findByText(/O plano mudou desde o dry-run; revise de novo/)).toBeInTheDocument()
    await waitFor(() => expect(getContractAbiHistoryPlan).toHaveBeenCalledTimes(2))
  })

  it('erro ao montar o plano (ex.: 503) usa o alerta de erro padrão', async () => {
    getContractAbiHistoryPlan.mockRejectedValue(new Error('Blockscout fora do ar'))
    renderDialog()

    expect(await screen.findByText('Blockscout fora do ar')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Aplicar importação/ })).toBeDisabled()
  })

  it('plano sem itens mostra estado vazio', async () => {
    getContractAbiHistoryPlan.mockResolvedValue({ data: plan([]) })
    renderDialog()

    expect(await screen.findByText(/Nenhum upgrade encontrado/)).toBeInTheDocument()
  })
})
