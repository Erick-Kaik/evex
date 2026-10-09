import { useState } from 'react'
import { isAxiosError } from 'axios'
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import { AbiHistorySource } from '../../api/api'
import type { AbiHistoryPlan, ApiError } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { AbiHistoryPlanTable } from './AbiHistoryPlanTable'
import { useAbiHistoryPlan, useImportAbiHistory } from './useContracts'

interface AbiHistoryImportDialogProps {
  address: string
  open: boolean
  onClose: () => void
  /** Abre o cadastro manual de ABI do mesmo contrato (usado nos itens PENDING). */
  onRegisterManual?: () => void
}

const SOURCE_OPTIONS = [
  { value: AbiHistorySource.Auto, label: 'Automática (ingeridos + Blockscout)' },
  { value: AbiHistorySource.IngestedOnly, label: 'Só eventos ingeridos' },
  { value: AbiHistorySource.BlockscoutOnly, label: 'Só Blockscout' },
] as const

function isPlanChangedError(error: unknown): boolean {
  return (
    isAxiosError<ApiError>(error) &&
    error.response?.status === 409 &&
    error.response.data?.code === 'ABI_HISTORY_PLAN_CHANGED'
  )
}

/**
 * Importação do histórico de versões de ABI de um proxy a partir do Blockscout (DEC-105), em 2
 * passos: (1) dry-run (`GET .../abi/history-plan`) mostrado numa tabela; (2) apply com confirmação
 * explícita (`POST .../abi/history-import`), mandando o `fingerprint` do plano exibido como
 * `expectedFingerprint` — se o plano mudou no servidor (409), recarrega e pede nova revisão.
 * `storage` não é enviado: o formulário de ABI existente também não o expõe (default do backend).
 */
export function AbiHistoryImportDialog({ address, open, onClose, onRegisterManual }: AbiHistoryImportDialogProps) {
  const [source, setSource] = useState<AbiHistorySource>(AbiHistorySource.Auto)
  const [replaceLegacy, setReplaceLegacy] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [result, setResult] = useState<AbiHistoryPlan | null>(null)
  const [planChanged, setPlanChanged] = useState(false)

  const plan = useAbiHistoryPlan(address, source, replaceLegacy, open)
  const importHistory = useImportAbiHistory(address)

  const planData = plan.data
  const applicable = planData?.items.filter((item) => item.status === 'APPLY').length ?? 0
  const pending = planData?.items.filter((item) => item.status === 'PENDING_NO_VERIFIED_ABI') ?? []
  const legacyToReplace = planData?.legacyVersionsToReplace ?? []
  const hasBlocked = planData?.items.some((item) => item.status === 'BLOCKED_AFTER_PENDING') ?? false

  function handleSourceChange(value: AbiHistorySource) {
    setSource(value)
    setResult(null)
    setPlanChanged(false)
    importHistory.reset()
  }

  function handleReplaceLegacyChange(value: boolean) {
    setReplaceLegacy(value)
    setResult(null)
    setPlanChanged(false)
    importHistory.reset()
  }

  function handleConfirm() {
    if (!planData) {
      return
    }
    importHistory.mutate(
      {
        historySource: source,
        expectedFingerprint: planData.fingerprint,
        replaceBlockscoutLegacy: replaceLegacy,
      },
      {
        onSuccess: (data) => {
          setResult(data)
          setPlanChanged(false)
          setConfirmOpen(false)
        },
        onError: (error) => {
          if (isPlanChangedError(error)) {
            setPlanChanged(true)
            setConfirmOpen(false)
            void plan.refetch()
          }
        },
      },
    )
  }

  function handleClose() {
    setResult(null)
    setPlanChanged(false)
    setConfirmOpen(false)
    importHistory.reset()
    onClose()
  }

  return (
    <>
      <Dialog open={open} onClose={handleClose} maxWidth="lg" fullWidth aria-labelledby="abi-history-title">
        <DialogTitle id="abi-history-title">Importar histórico de versões do Blockscout</DialogTitle>
        <DialogContent dividers>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Passo 1: revise o plano (nada é gravado). Passo 2: aplique com confirmação. Só aparece ABI de
            implementações <strong>verificadas</strong> no Blockscout.
          </Typography>

          <TextField
            select
            size="small"
            label="Fonte do histórico"
            value={source}
            onChange={(event) => handleSourceChange(event.target.value as AbiHistorySource)}
            sx={{ minWidth: 320, mb: 2 }}
          >
            {SOURCE_OPTIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>

          <Box sx={{ mb: 2 }}>
            <FormControlLabel
              control={
                <Checkbox
                  checked={replaceLegacy}
                  onChange={(event) => handleReplaceLegacyChange(event.target.checked)}
                />
              }
              label="Substituir versões legadas importadas do Blockscout"
            />
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', ml: 4 }}>
              Só apaga versões importadas do Blockscout com a ABI do proxy (cadastradas antes desta
              importação). Nunca apaga ABIs enviadas manualmente. O plano abaixo já considera a substituição.
            </Typography>
          </Box>

          {plan.isFetching && !planData && (
            <Typography variant="body2" color="text.secondary">
              Montando o plano...
            </Typography>
          )}
          {plan.isError && <ApiErrorAlert error={plan.error} />}

          {planChanged && (
            <Alert severity="warning" sx={{ mb: 2 }}>
              O plano mudou desde o dry-run; revise de novo (o plano abaixo foi recarregado).
            </Alert>
          )}

          {result && <ImportResult result={result} />}

          {planData && (
            <>
              <Stack direction="row" sx={{ gap: 3, flexWrap: 'wrap', mb: 1 }}>
                <Typography variant="body2">
                  Fonte efetiva: <strong>{planData.historySource}</strong>
                </Typography>
                <Typography variant="body2">
                  Ingerido até o bloco: <strong>{planData.ingestedUntilBlock}</strong>
                </Typography>
                <Typography variant="body2">
                  Histórico truncado: <strong>{planData.truncated ? 'sim' : 'não'}</strong>
                </Typography>
              </Stack>

              {planData.truncated && (
                <Alert severity="warning" sx={{ mb: 1 }}>
                  O histórico do Blockscout atingiu o limite de resultados e pode estar incompleto.
                </Alert>
              )}
              {planData.warnings.map((warning, index) => (
                <Alert key={`${index}-${warning}`} severity="warning" sx={{ mb: 1 }}>
                  {warning}
                </Alert>
              ))}

              {planData.items.length === 0 ? (
                <Alert severity="info">
                  Nenhum upgrade encontrado para este contrato na fonte escolhida. Ele pode não ser um
                  proxy upgradável, ou a ingestão ainda não alcançou os eventos <code>Upgraded</code>.
                </Alert>
              ) : (
                <AbiHistoryPlanTable items={planData.items} />
              )}

              {legacyToReplace.length > 0 && (
                <Alert severity="warning" sx={{ mt: 2 }}>
                  <AlertTitle>
                    {legacyToReplace.length} versão(ões) legada(s) será(ão) apagada(s)
                  </AlertTitle>
                  {legacyToReplace.map((legacy) => (
                    <Typography key={legacy.abiId} variant="body2">
                      ABI <code>{legacy.abiId.slice(0, 8)}</code> (a partir do bloco{' '}
                      <strong>{legacy.validFromBlock}</strong>)
                    </Typography>
                  ))}
                </Alert>
              )}

              {pending.length > 0 && (
                <Alert severity="warning" sx={{ mt: 2 }}>
                  <AlertTitle>Implementação sem ABI verificada</AlertTitle>
                  {pending.map((item) => (
                    <Typography key={item.sequence} variant="body2">
                      Item {item.sequence}: cadastre manualmente a ABI da implementação{' '}
                      <code>{item.implementationAddress}</code> a partir do bloco{' '}
                      <strong>{item.validFromBlock}</strong> (ou verifique-a no Blockscout e refaça o plano).
                    </Typography>
                  ))}
                  {hasBlocked && (
                    <Typography variant="body2" sx={{ mt: 0.5 }}>
                      Os itens seguintes ficam bloqueados até resolver o pendente, para não deixar buraco na
                      linha do tempo.
                    </Typography>
                  )}
                  {onRegisterManual && (
                    <Button size="small" sx={{ mt: 1 }} onClick={onRegisterManual}>
                      Cadastrar ABI manualmente
                    </Button>
                  )}
                </Alert>
              )}

            </>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={handleClose}>Fechar</Button>
          <Button variant="contained" disabled={!planData || applicable === 0} onClick={() => setConfirmOpen(true)}>
            Aplicar importação ({applicable})
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={confirmOpen} onClose={() => setConfirmOpen(false)} aria-labelledby="abi-history-confirm-title">
        <DialogTitle id="abi-history-confirm-title">Confirmar importação</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Serão cadastradas {applicable} versão(ões) de ABI neste contrato (itens APPLY do plano), em ordem de bloco, e um
            redecode do histórico será disparado.
            {legacyToReplace.length > 0 &&
              ` ${legacyToReplace.length} versão(ões) legada(s) importada(s) do Blockscout será(ão) apagada(s) antes (uploads manuais não são tocados).`}
          </DialogContentText>
          {importHistory.isError && !isPlanChangedError(importHistory.error) && (
            <ApiErrorAlert error={importHistory.error} />
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmOpen(false)}>Cancelar</Button>
          <Button variant="contained" disabled={importHistory.isPending} onClick={handleConfirm}>
            {importHistory.isPending ? 'Importando...' : 'Confirmar importação'}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}

function ImportResult({ result }: { result: AbiHistoryPlan }) {
  const applied = result.appliedCount ?? 0
  return (
    <Alert severity="success" sx={{ mb: 2 }} role="status">
      <AlertTitle>Importação concluída</AlertTitle>
      {applied} versão(ões) de ABI cadastrada(s).
      {applied > 0 && result.redecodeStarted === true && ' O redecode do histórico foi iniciado.'}
      {applied > 0 && result.redecodeStarted === false && (
        <>
          {' '}
          Já havia um redecode em curso, então um novo não foi disparado: dispare um redecode manual
          depois (<code>POST /v1/redecode</code>) para decodificar com as novas versões.
        </>
      )}
    </Alert>
  )
}
