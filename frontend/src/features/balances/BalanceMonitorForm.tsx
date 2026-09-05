import { useState } from 'react'
import type { FormEvent } from 'react'
import { Box, MenuItem, Stack, TextField, Typography } from '@mui/material'
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

  const isTransfer = behavior === BalanceBehavior.Transfer
  const isGuided = formMode === 'guided'
  const isVisual = formMode === 'visual'
  // Guiado e visual compartilham a cascata contrato→evento no topo (rede herdada do contrato).
  const usesContractRef = isGuided || isVisual

  const effectiveAddress = usesContractRef ? (contractRef?.address ?? '') : contractAddress
  const effectiveNetwork: Network | '' = usesContractRef ? (contractRef?.network ?? '') : network

  const canvasSlots: ParamBindingSlot[] = isTransfer
    ? [
        { key: 'from', label: 'Origem (from)', kind: 'address', value: fromParam },
        { key: 'to', label: 'Destino (to)', kind: 'address', value: toParam },
        { key: 'value', label: 'Montante', kind: 'numeric', value: valueParam },
        { key: 'tokenId', label: 'Token ID (opcional)', kind: 'numeric', value: tokenIdParam },
      ]
    : [
        { key: 'wallet', label: 'Carteira', kind: 'address', value: walletParam },
        { key: 'value', label: 'Montante/delta', kind: 'numeric', value: valueParam },
        { key: 'tokenId', label: 'Token ID (opcional)', kind: 'numeric', value: tokenIdParam },
      ]

  function handleCanvasBind(slotKey: string, paramName: string | null) {
    const next = paramName ?? ''
    if (slotKey === 'from') setFromParam(next)
    else if (slotKey === 'to') setToParam(next)
    else if (slotKey === 'wallet') setWalletParam(next)
    else if (slotKey === 'value') setValueParam(next)
    else if (slotKey === 'tokenId') setTokenIdParam(next)
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
                inputs={eventInputs}
                value={fromParam}
                onChange={setFromParam}
                label="Parâmetro de origem (from)"
                required
              />
              <EventParamSelect
                kind="address"
                inputs={eventInputs}
                value={toParam}
                onChange={setToParam}
                label="Parâmetro de destino (to)"
                required
              />
            </>
          ) : (
            <EventParamSelect
              kind="address"
              inputs={eventInputs}
              value={walletParam}
              onChange={setWalletParam}
              label="Parâmetro da carteira"
              required
            />
          )}
          <EventParamSelect
            kind="numeric"
            inputs={eventInputs}
            value={valueParam}
            onChange={setValueParam}
            label="Parâmetro do montante/delta"
            required
          />
          <EventParamSelect
            kind="numeric"
            inputs={eventInputs}
            value={tokenIdParam}
            onChange={setTokenIdParam}
            label="Parâmetro do tokenId (opcional)"
          />
        </>
      ) : isVisual ? (
        <ParamBindingCanvas params={eventInputs} slots={canvasSlots} onBind={handleCanvasBind} />
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
