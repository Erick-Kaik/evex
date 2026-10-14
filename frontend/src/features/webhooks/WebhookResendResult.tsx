import { Alert, AlertTitle, Box, Button, Typography } from '@mui/material'
import type { WebhookResendResult as ResendResult } from '../../api/api'
import { ModelStateInfo } from './WebhookPreviewResults'
import { ResolvedBlockInfo } from './ResolvedBlockInfo'
import { translateSkipReason, translateWarning } from './resendUtils'

interface WebhookResendResultProps {
  result: ResendResult
  labelOf: (eventId: string) => string
  onViewDeliveries: () => void
}

const NEWER = 'MODEL_STATE_NEWER_THAN_EVENT'

/** Resultado (202) do reenvio: aceitos, ignorados (com motivo), estado do modelo e avisos. */
export function WebhookResendResult({ result, labelOf, onViewDeliveries }: WebhookResendResultProps) {
  const warnings = (result.warnings ?? []).filter((warning) => warning !== NEWER)
  return (
    <Box aria-label="resultado do reenvio" sx={{ display: 'grid', gap: 1 }}>
      <Alert severity={result.accepted.length > 0 ? 'success' : 'warning'}>
        <AlertTitle>
          {result.accepted.length} evento(s) enfileirado(s) para reenvio; {result.skipped.length} ignorado(s)
        </AlertTitle>
        Os envios seguem a fila normal (com retry) e aparecem no histórico de entregas.
        {result.accepted.length > 0 && (
          <Box sx={{ mt: 1 }}>
            <Button size="small" variant="outlined" onClick={onViewDeliveries}>
              Ver histórico de entregas
            </Button>
          </Box>
        )}
      </Alert>

      {result.accepted.length > 0 && (
        <Box component="ul" sx={{ m: 0, pl: 3 }}>
          {result.accepted.map((accepted) => (
            <li key={accepted.deliveryId}>
              <Typography variant="body2">
                {labelOf(accepted.eventId)} — entrega <span style={{ fontFamily: 'monospace' }}>{accepted.deliveryId}</span>
              </Typography>
            </li>
          ))}
        </Box>
      )}

      {result.skipped.length > 0 && (
        <Alert severity="warning">
          <AlertTitle>Ignorados</AlertTitle>
          <Box component="ul" sx={{ m: 0, pl: 2 }}>
            {result.skipped.map((skipped) => (
              <li key={`${skipped.eventId}-${skipped.reason}`}>
                {labelOf(skipped.eventId)}: {translateSkipReason(skipped.reason)}
              </li>
            ))}
          </Box>
        </Alert>
      )}

      <ModelStateInfo modelState={result.modelState} warnings={result.warnings} />
      <ResolvedBlockInfo block={result.enrichmentBlock} />
      {warnings.map((warning) => (
        <Alert key={warning} severity="info">
          {translateWarning(warning)}
        </Alert>
      ))}
    </Box>
  )
}
