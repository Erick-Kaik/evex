import { Alert, Box, Chip, Paper, Stack, Typography } from '@mui/material'
import type { ModelState, WebhookPreviewItem, WebhookPreviewResult } from '../../api/api'
import { DiagnosesList, EnrichmentCallsTable, FailuresSummaryList } from './EnrichmentCallsView'
import { JsonBlock } from './JsonBlock'
import { ResolvedBlockInfo } from './ResolvedBlockInfo'
import { translateNotMatchingReason, translateWarning } from './resendUtils'

const NEWER = 'MODEL_STATE_NEWER_THAN_EVENT'

/** Estado do modelo + aviso de que o payload usa o estado ATUAL. */
export function ModelStateInfo({ modelState, warnings }: { modelState?: ModelState; warnings?: string[] }) {
  const newer = modelState?.newerThanEvent === true || (warnings ?? []).includes(NEWER)
  if (!modelState && !newer) return null
  return (
    <>
      {modelState?.stateAt && (
        <Typography variant="body2" color="text.secondary">
          Estado do modelo em: {new Date(modelState.stateAt).toLocaleString('pt-BR')}
        </Typography>
      )}
      {newer && (
        <Alert severity="warning" sx={{ mt: 0.5 }}>
          {translateWarning(NEWER)}
        </Alert>
      )}
    </>
  )
}

function PreviewItemCard({ item, label }: { item: WebhookPreviewItem; label: string }) {
  const otherWarnings = (item.warnings ?? []).filter((warning) => warning !== NEWER)
  const enrichment = item.enrichment
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 1 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
          {label}
        </Typography>
        {item.matches ? (
          <Chip size="small" color="success" label="Casa com o webhook" />
        ) : (
          <Chip
            size="small"
            color="warning"
            label={`Não casa${item.notMatchingReason ? `: ${translateNotMatchingReason(item.notMatchingReason)}` : ''}`}
          />
        )}
      </Stack>
      {!item.matches && (
        <Alert severity="warning" sx={{ mb: 1 }}>
          Este evento NÃO casaria com o webhook e não seria enviado; o payload abaixo é só uma renderização de teste.
        </Alert>
      )}

      <JsonBlock title="Payload final" value={item.payload} emptyMessage="Payload indisponível: não foi possível renderizá-lo para este evento." />

      <ModelStateInfo modelState={item.modelState} warnings={item.warnings} />
      {otherWarnings.map((warning) => (
        <Alert key={warning} severity="info" sx={{ mt: 0.5 }}>
          {translateWarning(warning)}
        </Alert>
      ))}

      {enrichment && (
        <Box sx={{ mt: 2 }}>
          <Typography variant="subtitle2">
            Enriquecimento{enrichment.blockUsed ? ` (bloco ${enrichment.blockUsed})` : ''}
          </Typography>
          <EnrichmentCallsTable calls={enrichment.calls} />
          {enrichment.unresolvedTokens.length > 0 && (
            <Alert severity="warning" sx={{ mt: 1 }}>
              Tokens do template sem valor (ficam <code>null</code> no payload):{' '}
              <span style={{ fontFamily: 'monospace' }}>{enrichment.unresolvedTokens.join(', ')}</span>
            </Alert>
          )}
          <FailuresSummaryList failures={enrichment.failuresSummary} />
          <DiagnosesList diagnoses={enrichment.diagnoses} />
        </Box>
      )}
    </Paper>
  )
}

interface WebhookPreviewResultsProps {
  result: WebhookPreviewResult
  labelOf: (eventId: string) => string
}

/** Resultado de `POST /preview`: um cartão por evento + resumo agregado e avisos da requisição. */
export function WebhookPreviewResults({ result, labelOf }: WebhookPreviewResultsProps) {
  return (
    <Stack spacing={2} aria-label="resultado da pré-visualização">
      <Alert severity="success">Pré-visualização concluída. Nada foi enviado nem gravado.</Alert>
      <ResolvedBlockInfo block={result.enrichmentBlock} />
      {(result.warnings ?? []).map((warning) => (
        <Alert key={warning} severity="info">
          {translateWarning(warning)}
        </Alert>
      ))}
      <FailuresSummaryList failures={result.failuresSummary} />
      {result.items.map((item) => (
        <PreviewItemCard key={item.eventId} item={item} label={labelOf(item.eventId)} />
      ))}
    </Stack>
  )
}
