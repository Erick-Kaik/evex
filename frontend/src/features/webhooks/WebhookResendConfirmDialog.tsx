import { useState } from 'react'
import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Radio,
  RadioGroup,
  TextField,
  Typography,
} from '@mui/material'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { blockModeLabel, errorHint } from './resendUtils'
import type { ParsedApiError } from './resendUtils'

export interface ResendConfirmation {
  /** `true` = reenviar o intervalo do filtro (`range`) em vez dos ids selecionados. */
  useRange: boolean
  overrideUrl?: string
}

interface WebhookResendConfirmDialogProps {
  open: boolean
  selectedCount: number
  resendMaxEvents: number
  /** O filtro aplicado tem algum limite (data/bloco), então `range` faz sentido. */
  rangeAvailable: boolean
  blockMode: string
  webhookActive: boolean
  urlOverrideEnabled: boolean
  isPending: boolean
  error: unknown
  parsedError: ParsedApiError | null
  /** Segundos restantes de espera após um 429 (0 = liberado). */
  waitSeconds: number
  onCancel: () => void
  onConfirm: (confirmation: ResendConfirmation) => void
}

/** Confirmação explícita do reenvio: resumo, avisos honestos, `range` opcional e URL de teste (só com a capacidade ligada). */
export function WebhookResendConfirmDialog(props: WebhookResendConfirmDialogProps) {
  const [useRange, setUseRange] = useState(false)
  const [useOverride, setUseOverride] = useState(false)
  const [overrideUrl, setOverrideUrl] = useState('')
  const rangeOnly = props.rangeAvailable && props.selectedCount === 0
  const overrideActive = props.urlOverrideEnabled && useOverride
  const hint = props.parsedError ? errorHint(props.parsedError) : undefined

  return (
    <Dialog open={props.open} onClose={props.onCancel} maxWidth="sm" fullWidth aria-labelledby="resend-confirm-title">
      <DialogTitle id="resend-confirm-title">Confirmar reenvio</DialogTitle>
      <DialogContent dividers>
        {props.rangeAvailable ? (
          <RadioGroup value={useRange || rangeOnly ? 'range' : 'ids'} onChange={(event) => setUseRange(event.target.value === 'range')} aria-label="o que reenviar">
            <FormControlLabel value="ids" disabled={rangeOnly} control={<Radio />} label={`${props.selectedCount} evento(s) selecionado(s)`} />
            <FormControlLabel
              value="range"
              control={<Radio />}
              label={`Todos os eventos que casam no intervalo do filtro (até ${props.resendMaxEvents}; o excedente é truncado com aviso)`}
            />
          </RadioGroup>
        ) : (
          <Typography variant="body1">{props.selectedCount} evento(s) serão enfileirados.</Typography>
        )}
        <Typography variant="body2" sx={{ mt: 1 }}>
          Modo de bloco do enriquecimento: <strong>{blockModeLabel(props.blockMode)}</strong>.
        </Typography>

        <Alert severity="warning" sx={{ mt: 2 }}>
          O payload usa o estado ATUAL do modelo, que pode ter avançado desde o evento. O reenvio NÃO altera os eventos
          decodificados, saldos nem modelos: só cria novas entregas, que entram na fila normal e têm retry.
        </Alert>
        {!props.webhookActive && (
          <Alert severity="info" sx={{ mt: 1 }}>
            Este webhook está pausado: o reenvio só é aceito com uma URL de teste válida.
          </Alert>
        )}

        {props.urlOverrideEnabled && (
          <>
            <FormControlLabel
              sx={{ mt: 1 }}
              control={<Checkbox checked={useOverride} onChange={(event) => setUseOverride(event.target.checked)} />}
              label="Enviar para uma URL de teste"
            />
            {useOverride && (
              <>
                <TextField
                  size="small"
                  fullWidth
                  label="URL de teste"
                  value={overrideUrl}
                  onChange={(event) => setOverrideUrl(event.target.value)}
                />
                <Typography variant="caption" color="text.secondary" component="div">
                  Não altera o webhook cadastrado. A política de destino do servidor pode bloquear a URL, e a resposta do
                  destino não é gravada no histórico.
                </Typography>
              </>
            )}
          </>
        )}

        {props.error != null && <ApiErrorAlert error={props.error} />}
        {hint && (
          <Alert severity="info" sx={{ mt: 1 }}>
            {hint}
          </Alert>
        )}
        {props.waitSeconds > 0 && (
          <Alert severity="warning" sx={{ mt: 1 }}>
            Tente novamente em {props.waitSeconds} s.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={props.onCancel}>Cancelar</Button>
        <Button
          variant="contained"
          disabled={props.isPending || props.waitSeconds > 0 || (overrideActive && overrideUrl.trim() === '')}
          onClick={() =>
            props.onConfirm({
              useRange: props.rangeAvailable && (useRange || rangeOnly),
              overrideUrl: overrideActive ? overrideUrl.trim() : undefined,
            })
          }
        >
          {props.isPending ? 'Enfileirando...' : 'Confirmar reenvio'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
