import { useMemo, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  Stack,
  Typography,
} from '@mui/material'
import type { Event as IndexedEvent } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { DEFAULT_BLOCK_MODE, blockModeError, toBlockSpec } from './blockMode'
import type { BlockModeValue } from './blockMode'
import { EnrichmentBlockModeFields } from './EnrichmentBlockModeFields'
import { MatchingEventsSelector } from './MatchingEventsSelector'
import { WebhookPreviewResults } from './WebhookPreviewResults'
import { WebhookResendConfirmDialog } from './WebhookResendConfirmDialog'
import type { ResendConfirmation } from './WebhookResendConfirmDialog'
import { WebhookResendResult } from './WebhookResendResult'
import { parseApiError } from './resendUtils'
import type { ParsedApiError } from './resendUtils'
import {
  useCapabilityLimits,
  useRetryWait,
  useMatchingEvents,
  usePreviewWebhook,
  useResendWebhook,
} from './useWebhookResend'
import type { MatchingEventsFilter } from './useWebhookResend'

interface WebhookResendDialogProps {
  webhookId: string
  webhookActive: boolean
  open: boolean
  /** Qual botão abriu o diálogo (muda só o título; as duas ações ficam disponíveis). */
  intent: 'preview' | 'resend'
  onClose: () => void
  /** Leva o usuário ao histórico de entregas do webhook. */
  onViewDeliveries: () => void
}

function hasBound(filter: MatchingEventsFilter): boolean {
  return Object.values(filter).some((value) => value !== undefined)
}

/**
 * Pré-visualização e reenvio de eventos de um webhook (E33, DEC-113): (1) escolhe eventos que casam
 * (`matching-events`), (2) define o bloco do enriquecimento, (3) pré-visualiza (dry-run: nada é
 * enviado nem gravado) ou reenvia (com confirmação explícita; entra na fila normal com retry).
 */
export function WebhookResendDialog({ webhookId, webhookActive, open, intent, onClose, onViewDeliveries }: WebhookResendDialogProps) {
  const limits = useCapabilityLimits()
  const [filter, setFilter] = useState<MatchingEventsFilter>({})
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [blockMode, setBlockMode] = useState<BlockModeValue>(DEFAULT_BLOCK_MODE)
  const [diagnose, setDiagnose] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [resendError, setResendError] = useState<ParsedApiError | null>(null)

  const matching = useMatchingEvents(webhookId, filter, open)
  const preview = usePreviewWebhook(webhookId)
  const resend = useResendWebhook(webhookId)
  const previewRetry = useRetryWait()
  const resendRetry = useRetryWait()
  const previewWait = previewRetry.seconds
  const resendWait = resendRetry.seconds

  const pages = matching.data?.pages
  const events = useMemo(() => pages?.flatMap((page) => page.content) ?? [], [pages])
  const eventsById = useMemo(() => new Map<string, IndexedEvent>(events.map((event) => [event.id, event])), [events])
  const lastPage = pages?.[pages.length - 1]
  const scanned = pages?.reduce((total, page) => total + page.scanned, 0) ?? 0
  const warnings = [...new Set(pages?.flatMap((page) => page.warnings ?? []) ?? [])]

  function labelOf(eventId: string): string {
    const event = eventsById.get(eventId)
    if (!event) return `Evento ${eventId}`
    return `Bloco ${event.blockNumber} · log ${event.logIndex} · ${event.eventName ?? 'evento'}`
  }

  const modeError = blockModeError(blockMode)
  const previewBlocked =
    selected.size === 0 || selected.size > limits.previewMaxEvents || modeError !== null || preview.isPending || previewWait > 0
  const resendBlocked = selected.size > limits.resendMaxEvents || modeError !== null || resendWait > 0

  function handleApplyFilter(next: MatchingEventsFilter) {
    setFilter(next)
    setSelected(new Set())
    preview.reset()
    resend.reset()
  }

  function handlePreview() {
    preview.mutate(
      { eventIds: [...selected], enrichmentBlock: toBlockSpec(blockMode), diagnose },
      {
        onError: (error) => {
          const parsed = parseApiError(error)
          if (parsed.retryAfterSeconds) previewRetry.start(parsed.retryAfterSeconds)
        },
      },
    )
  }

  function handleConfirmResend(confirmation: ResendConfirmation) {
    setResendError(null)
    resend.mutate(
      {
        ...(confirmation.useRange ? { range: filter } : { eventIds: [...selected] }),
        enrichmentBlock: toBlockSpec(blockMode),
        ...(confirmation.overrideUrl ? { overrideUrl: confirmation.overrideUrl } : {}),
      },
      {
        onSuccess: () => setConfirmOpen(false),
        onError: (error) => {
          const parsed = parseApiError(error)
          setResendError(parsed)
          if (parsed.retryAfterSeconds) resendRetry.start(parsed.retryAfterSeconds)
        },
      },
    )
  }

  function openConfirm() {
    resend.reset()
    setResendError(null)
    setConfirmOpen(true)
  }

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="lg" fullWidth aria-labelledby="webhook-resend-title">
        <DialogTitle id="webhook-resend-title">
          {intent === 'preview' ? 'Pré-visualizar webhook' : 'Reenviar webhook'}
        </DialogTitle>
        <DialogContent dividers>
          <Stack spacing={3}>
            <Box>
              <Typography variant="h6" sx={{ mb: 1 }}>
                1. Eventos que casam com este webhook
              </Typography>
              <MatchingEventsSelector
                events={events}
                isPending={matching.isPending}
                error={matching.error}
                hasNextPage={matching.hasNextPage}
                isFetchingNextPage={matching.isFetchingNextPage}
                onLoadMore={() => void matching.fetchNextPage()}
                scanned={scanned}
                scanTruncated={lastPage?.scanTruncated === true}
                warnings={warnings}
                onApplyFilter={handleApplyFilter}
                selected={selected}
                onSelectedChange={setSelected}
                limits={limits}
              />
            </Box>

            <Divider />

            <Box>
              <Typography variant="h6" sx={{ mb: 1 }}>
                2. Bloco do enriquecimento
              </Typography>
              <EnrichmentBlockModeFields value={blockMode} onChange={setBlockMode} />
              {modeError && (
                <Alert severity="warning" sx={{ mt: 1 }}>
                  {modeError}
                </Alert>
              )}
            </Box>

            <Divider />

            <Box>
              <Typography variant="h6" sx={{ mb: 1 }}>
                3. Pré-visualizar ou reenviar
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                A pré-visualização é um dry-run: nada é enviado nem gravado.
              </Typography>
              <Stack direction="row" sx={{ gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
                <Button variant={intent === 'preview' ? 'contained' : 'outlined'} onClick={handlePreview} disabled={previewBlocked}>
                  {preview.isPending ? 'Pré-visualizando...' : previewWait > 0 ? `Aguarde ${previewWait} s` : 'Pré-visualizar'}
                </Button>
                <FormControlLabel
                  control={<Checkbox checked={diagnose} onChange={(event) => setDiagnose(event.target.checked)} />}
                  label="Diagnosticar falhas (diagnose)"
                />
                <Button
                  variant={intent === 'resend' ? 'contained' : 'outlined'}
                  color="warning"
                  onClick={openConfirm}
                  disabled={resendBlocked || (selected.size === 0 && !hasBound(filter))}
                >
                  {resendWait > 0 ? `Aguarde ${resendWait} s` : 'Reenviar...'}
                </Button>
              </Stack>

              {preview.isError && (
                <>
                  <ApiErrorAlert error={preview.error} />
                  {previewWait > 0 && (
                    <Alert severity="warning" sx={{ mt: 1 }}>
                      Tente novamente em {previewWait} s.
                    </Alert>
                  )}
                </>
              )}
              {preview.data && (
                <Box sx={{ mt: 2 }}>
                  <WebhookPreviewResults result={preview.data} labelOf={labelOf} />
                </Box>
              )}
              {resend.data && (
                <Box sx={{ mt: 2 }}>
                  <WebhookResendResult result={resend.data} labelOf={labelOf} onViewDeliveries={onViewDeliveries} />
                </Box>
              )}
            </Box>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Fechar</Button>
        </DialogActions>
      </Dialog>

      <WebhookResendConfirmDialog
        open={confirmOpen}
        selectedCount={selected.size}
        resendMaxEvents={limits.resendMaxEvents}
        rangeAvailable={hasBound(filter)}
        blockMode={blockMode.mode}
        webhookActive={webhookActive}
        urlOverrideEnabled={limits.urlOverrideEnabled}
        isPending={resend.isPending}
        error={resend.error}
        parsedError={resendError}
        waitSeconds={resendWait}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={handleConfirmResend}
      />
    </>
  )
}
