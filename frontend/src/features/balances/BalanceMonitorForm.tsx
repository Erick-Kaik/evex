import { useState } from 'react'
import type { FormEvent } from 'react'
import { Alert, Box, Checkbox, Chip, FormControlLabel, MenuItem, Stack, TextField, Typography } from '@mui/material'
import Button from '@mui/material/Button'
import { BalanceBehavior } from '../../api/api'
import type { ContractEventInput, CreateBalanceMonitorPayload, Network } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { EventPicker } from '../../components/EventPicker'
import { EventParamSelect } from '../../components/EventParamSelect'
import { ParamBindingCanvas } from '../../components/ParamBindingCanvas'
import type { ParamBindingSlot } from '../../components/ParamBindingCanvas'
import { isArrayType, isNumericArrayType, useContractEventCatalog } from '../../components/contractCatalog'
import { FormModeToggle } from '../../components/FormModeToggle'
import { readFormMode } from '../../components/formMode'
import type { FormMode } from '../../components/formMode'
import { LabeledField } from '../../components/LabeledField'
import type { PageMode } from '../../components/pageMode'

export type BalanceMonitorFormValues = CreateBalanceMonitorPayload

const FORM_MODE_KEY = 'balance-monitor'
const FORM_MODES: FormMode[] = ['guided', 'visual', 'manual']

interface BalanceMonitorFormProps {
  /** `view` (só leitura), `edit` (edição de configuração existente) ou `create` (padrão E18). */
  mode: PageMode
  initialValues?: Partial<BalanceMonitorFormValues>
  submitLabel: string
  isPending: boolean
  isError: boolean
  error: unknown
  isSuccess?: boolean
  successMessage?: string
  onSubmit: (payload: BalanceMonitorFormValues) => void
}

/**
 * Form de cadastro/edição de uma configuração de monitoramento de saldo (`BalanceMonitorConfig`,
 * doc §4.4), com campos condicionais por `behavior` — mesma regra de validação que o backend já
 * aplica (`CreateBalanceMonitorPayload`), replicada aqui só pra dar feedback antes do submit.
 * Extraído de `BalanceMonitorSection` na E18 para virar o componente único compartilhado entre
 * `create`/`edit`/`view` (padrão E18, DEC-079) — a listagem (`BalanceMonitorListSection`) não tem
 * mais formulário embutido.
 *
 * Três modos de preenchimento em `edit`/`create` (DEC-072): **guiado** (contrato/evento/parâmetros em
 * cascata sobre os dados cadastrados, rede herdada do contrato), **visual** (mesma cascata no topo +
 * `<ParamBindingCanvas>`) e **manual** (texto livre + `<NetworkSelect>`, comportamento histórico). Em
 * `view` os 3 modos não se aplicam — os campos aparecem como texto (`<LabeledField mode="view">`).
 */
export function BalanceMonitorForm({
  mode,
  initialValues,
  submitLabel,
  isPending,
  isError,
  error,
  isSuccess,
  successMessage,
  onSubmit,
}: BalanceMonitorFormProps) {
  const [formMode, setFormMode] = useState<FormMode>(() => readFormMode(FORM_MODE_KEY, FORM_MODES))

  // Estado do modo manual.
  const [contractAddress, setContractAddress] = useState(initialValues?.contractAddress ?? '')
  const [network, setNetwork] = useState<Network | ''>(initialValues?.network ?? '')
  // Estado do modo guiado/visual.
  const [contractRef, setContractRef] = useState<ContractRef | null>(
    initialValues?.contractAddress && initialValues?.network
      ? { address: initialValues.contractAddress, network: initialValues.network }
      : null,
  )
  const [eventInputs, setEventInputs] = useState<ContractEventInput[]>([])

  const [eventName, setEventName] = useState(initialValues?.eventName ?? '')
  const [behavior, setBehavior] = useState<BalanceBehavior>(initialValues?.behavior ?? BalanceBehavior.Credit)
  const [walletParam, setWalletParam] = useState(initialValues?.walletParam ?? '')
  const [fromParam, setFromParam] = useState(initialValues?.fromParam ?? '')
  const [toParam, setToParam] = useState(initialValues?.toParam ?? '')
  const [valueParam, setValueParam] = useState(initialValues?.valueParam ?? '')
  const [tokenIdParam, setTokenIdParam] = useState(initialValues?.tokenIdParam ?? '')
  // `batch` (DEC-110): sugerido automaticamente a partir dos tipos do ABI até o usuário mexer no checkbox.
  const [batch, setBatch] = useState(initialValues?.batch ?? false)
  const [batchTouched, setBatchTouched] = useState(false)

  const isTransfer = behavior === BalanceBehavior.Transfer
  const isGuided = formMode === 'guided'
  const isVisual = formMode === 'visual'
  // Guiado e visual compartilham a cascata contrato→evento no topo (rede herdada do contrato).
  const usesContractRef = isGuided || isVisual

  const effectiveAddress = usesContractRef ? (contractRef?.address ?? '') : contractAddress
  const effectiveNetwork: Network | '' = usesContractRef ? (contractRef?.network ?? '') : network

  // Tipos Solidity dos parâmetros: dos `inputs` do evento escolhido (ou, na edição, do catálogo do
  // contrato). No modo manual o contrato é texto livre — sem catálogo, sem validação de tipo no front.
  const catalog = useContractEventCatalog(usesContractRef ? effectiveAddress || undefined : undefined)
  const activeInputs = eventInputs.length > 0 ? eventInputs : (catalog.inputsByEvent.get(eventName) ?? [])
  const typeOf = (paramName: string): string | undefined =>
    paramName ? activeInputs.find((input) => input.name === paramName)?.type : undefined

  // Slots numéricos aceitam escalar ou array: o `batch` é inferido ao vincular (ver `suggestBatchFromBind`).
  const arraySuffix = batch ? ' (array)' : ''
  const canvasSlots: ParamBindingSlot[] = isTransfer
    ? [
        { key: 'from', label: 'Origem (from)', kind: 'address', value: fromParam },
        { key: 'to', label: 'Destino (to)', kind: 'address', value: toParam },
        { key: 'value', label: `Montante${arraySuffix}`, kind: 'numeric', value: valueParam },
        { key: 'tokenId', label: `Token ID (opcional)${arraySuffix}`, kind: 'numeric', value: tokenIdParam },
      ]
    : [
        { key: 'wallet', label: 'Carteira', kind: 'address', value: walletParam },
        { key: 'value', label: `Montante/delta${arraySuffix}`, kind: 'numeric', value: valueParam },
        { key: 'tokenId', label: `Token ID (opcional)${arraySuffix}`, kind: 'numeric', value: tokenIdParam },
      ]

  /** Visual: vincular um array numérico a montante/tokenId sugere `batch=true` (se o usuário ainda não decidiu). */
  function suggestBatchFromBind(slotKey: string, paramName: string) {
    if (batchTouched || !paramName) return
    const type = typeOf(paramName)
    if (type === undefined) return
    if (slotKey === 'value') setBatch(isNumericArrayType(type))
    else if (slotKey === 'tokenId' && isNumericArrayType(type)) setBatch(true)
  }

  function handleCanvasBind(slotKey: string, paramName: string | null) {
    const next = paramName ?? ''
    if (slotKey === 'from') setFromParam(next)
    else if (slotKey === 'to') setToParam(next)
    else if (slotKey === 'wallet') setWalletParam(next)
    else if (slotKey === 'value') setValueParam(next)
    else if (slotKey === 'tokenId') setTokenIdParam(next)
    suggestBatchFromBind(slotKey, next)
  }

  function handleBatchToggle(next: boolean) {
    setBatch(next)
    setBatchTouched(true)
    // No guiado o select só lista o tipo compatível: descarta escolhas que deixaram de valer.
    if (isGuided) {
      const mismatch = (name: string) => {
        const type = typeOf(name)
        return type !== undefined && isNumericArrayType(type) !== next
      }
      if (mismatch(valueParam)) setValueParam('')
      if (mismatch(tokenIdParam)) setTokenIdParam('')
    }
  }

  // Validação de tipos (só quando os tipos são conhecidos). Não bloqueia o envio: o backend é a
  // garantia final (400 se NENHUMA versão de ABI for compatível; aviso se só algumas).
  const batchTypeIssues: string[] = []
  for (const [label, name] of [
    ['montante/delta', valueParam],
    ['tokenId', tokenIdParam],
  ] as const) {
    const type = typeOf(name)
    if (type === undefined) continue
    if (batch && !isNumericArrayType(type)) {
      batchTypeIssues.push(
        `O parâmetro de ${label} "${name}" (${type}) não é um array numérico; com "Lote" ativado ele precisa ser um array (ex.: uint256[]).`,
      )
    } else if (!batch && isArrayType(type)) {
      batchTypeIssues.push(
        `O parâmetro de ${label} "${name}" (${type}) é um array; marque "Lote" ou escolha um parâmetro escalar.`,
      )
    }
  }

  function handleContractRefChange(next: ContractRef | null) {
    setContractRef(next)
    // Trocar de contrato invalida evento/parâmetros escolhidos.
    setEventName('')
    setEventInputs([])
    setWalletParam('')
    setFromParam('')
    setToParam('')
    setValueParam('')
    setTokenIdParam('')
  }

  function handleEventChange(name: string, inputs: ContractEventInput[]) {
    setEventName(name)
    setEventInputs(inputs)
    // Sugere `batch` quando o evento só oferece arrays numéricos (ex.: TransferBatch); o usuário pode desmarcar.
    if (!batchTouched) {
      const hasArray = inputs.some((input) => isNumericArrayType(input.type))
      const hasScalar = inputs.some((input) => /^u?int\d*$/.test(input.type))
      setBatch(hasArray && !hasScalar)
    }
    setWalletParam('')
    setFromParam('')
    setToParam('')
    setValueParam('')
    setTokenIdParam('')
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (effectiveNetwork === '') {
      return
    }

    onSubmit({
      contractAddress: effectiveAddress,
      network: effectiveNetwork,
      eventName,
      behavior,
      walletParam: isTransfer ? undefined : walletParam,
      fromParam: isTransfer ? fromParam : undefined,
      toParam: isTransfer ? toParam : undefined,
      valueParam,
      tokenIdParam: tokenIdParam || undefined,
      batch,
    })
  }

  if (mode === 'view') {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 480 }}>
        <LabeledField label="Contrato" mode="view" value={contractAddress} monospace />
        <LabeledField label="Rede" mode="view" value={network} />
        <LabeledField label="Nome do evento" mode="view" value={eventName} />
        <LabeledField label="Comportamento" mode="view" value={behavior} />
        {isTransfer ? (
          <>
            <LabeledField label="Parâmetro de origem (from)" mode="view" value={fromParam} />
            <LabeledField label="Parâmetro de destino (to)" mode="view" value={toParam} />
          </>
        ) : (
          <LabeledField label="Parâmetro da carteira" mode="view" value={walletParam} />
        )}
        <LabeledField label="Parâmetro do montante/delta" mode="view" value={valueParam} />
        <LabeledField label="Parâmetro do tokenId (opcional)" mode="view" value={tokenIdParam} />
        <Box>
          <Typography variant="caption" color="text.secondary" component="div">
            Lote (batch)
          </Typography>
          {batch ? <Chip size="small" color="info" label="Lote" /> : <Typography variant="body2">Não</Typography>}
        </Box>
      </Box>
    )
  }

  return (
    <Box component="form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 480 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="subtitle2">Dados da configuração</Typography>
        <FormModeToggle
          storageKey={FORM_MODE_KEY}
          modes={FORM_MODES}
          value={formMode}
          onChange={setFormMode}
        />
      </Stack>

      {usesContractRef ? (
        <>
          <ContractAutocomplete value={contractRef} onChange={handleContractRefChange} required />
          <EventPicker
            address={effectiveAddress || undefined}
            value={eventName}
            onChange={handleEventChange}
            required
          />
        </>
      ) : (
        <>
          <TextField
            label="Contrato"
            required
            fullWidth
            value={contractAddress}
            onChange={(event) => setContractAddress(event.target.value)}
            placeholder="0x..."
            slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
          />
          <NetworkSelect required value={network} onChange={setNetwork} />
          <TextField
            label="Nome do evento"
            required
            fullWidth
            value={eventName}
            onChange={(event) => setEventName(event.target.value)}
            placeholder="ex.: Transfer"
          />
        </>
      )}

      <TextField
        select
        label="Comportamento"
        fullWidth
        value={behavior}
        onChange={(event) => setBehavior(event.target.value as BalanceBehavior)}
      >
        <MenuItem value={BalanceBehavior.Credit}>CREDIT</MenuItem>
        <MenuItem value={BalanceBehavior.Debit}>DEBIT</MenuItem>
        <MenuItem value={BalanceBehavior.Delta}>DELTA</MenuItem>
        <MenuItem value={BalanceBehavior.Transfer}>TRANSFER</MenuItem>
      </TextField>

      {isGuided ? (
        <>
          {isTransfer ? (
            <>
              <EventParamSelect
                kind="address"
                inputs={activeInputs}
                value={fromParam}
                onChange={setFromParam}
                label="Parâmetro de origem (from)"
                required
              />
              <EventParamSelect
                kind="address"
                inputs={activeInputs}
                value={toParam}
                onChange={setToParam}
                label="Parâmetro de destino (to)"
                required
              />
            </>
          ) : (
            <EventParamSelect
              kind="address"
              inputs={activeInputs}
              value={walletParam}
              onChange={setWalletParam}
              label="Parâmetro da carteira"
              required
            />
          )}
          <EventParamSelect
            kind={batch ? 'numericArray' : 'numericScalar'}
            inputs={activeInputs}
            value={valueParam}
            onChange={setValueParam}
            label={`Parâmetro do montante/delta${arraySuffix}`}
            required
          />
          <EventParamSelect
            kind={batch ? 'numericArray' : 'numericScalar'}
            inputs={activeInputs}
            value={tokenIdParam}
            onChange={setTokenIdParam}
            label={`Parâmetro do tokenId (opcional)${arraySuffix}`}
          />
        </>
      ) : isVisual ? (
        <ParamBindingCanvas params={activeInputs} slots={canvasSlots} onBind={handleCanvasBind} />
      ) : (
        <>
          {isTransfer ? (
            <>
              <TextField
                label="Parâmetro de origem (from)"
                required
                fullWidth
                value={fromParam}
                onChange={(event) => setFromParam(event.target.value)}
              />
              <TextField
                label="Parâmetro de destino (to)"
                required
                fullWidth
                value={toParam}
                onChange={(event) => setToParam(event.target.value)}
              />
            </>
          ) : (
            <TextField
              label="Parâmetro da carteira"
              required
              fullWidth
              value={walletParam}
              onChange={(event) => setWalletParam(event.target.value)}
            />
          )}
          <TextField
            label="Parâmetro do montante/delta"
            required
            fullWidth
            value={valueParam}
            onChange={(event) => setValueParam(event.target.value)}
          />
          <TextField
            label="Parâmetro do tokenId (opcional)"
            fullWidth
            value={tokenIdParam}
            onChange={(event) => setTokenIdParam(event.target.value)}
          />
        </>
      )}

      <Box>
        <FormControlLabel
          control={<Checkbox checked={batch} onChange={(event) => handleBatchToggle(event.target.checked)} />}
          label="Lote (batch)"
        />
        <Typography variant="caption" color="text.secondary" component="div">
          Marque para eventos que emitem arrays (ex.: TransferBatch do ERC-1155): montante e tokenId são arrays e
          cada posição vira um lançamento. ERC-1155 precisa de 2 configurações por contrato — uma para o evento
          escalar (TransferSingle) e outra, com Lote, para o evento em lote. Editar para Lote não reprojeta o
          histórico: crie uma configuração nova e rode o reprocessamento. Evento inválido (arrays de tamanhos
          diferentes ou valor não numérico) é descartado inteiro, com WARN no log.
        </Typography>
      </Box>
      {batchTypeIssues.map((issue) => (
        <Alert key={issue} severity="warning">
          {issue}
        </Alert>
      ))}

      {isError && <ApiErrorAlert error={error} />}
      {isSuccess && successMessage && (
        <Typography variant="body2" color="success.main">
          {successMessage}
        </Typography>
      )}

      <Button type="submit" variant="contained" disabled={isPending} sx={{ alignSelf: 'flex-start' }}>
        {isPending ? 'Salvando...' : submitLabel}
      </Button>
    </Box>
  )
}
