import { Fragment, useState } from 'react'
import {
  Box,
  Button,
  Chip,
  Collapse,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material'
import type { WebhookDelivery, WebhookDeliveryAttempt } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { Pagination } from '../../components/Pagination'
import { EnrichmentCallsTable } from './EnrichmentCallsView'
import { JsonBlock } from './JsonBlock'
import { blockModeLabel } from './resendUtils'
import { useWebhookDeliveries } from './useWebhooks'

const PAGE_SIZE = 20

/** Origem da entrega: `RESEND` = reenvio, `MATCH`/ausente = normal; valor desconhecido = texto cru. */
function OriginChip({ origin }: { origin: string | undefined }) {
  if (!origin || origin === 'MATCH') return <Chip size="small" label="Normal" />
  if (origin === 'RESEND') return <Chip size="small" color="info" label="Reenvio" />
  return <Chip size="small" label={origin} />
}

function hasAttemptDetails(attempt: WebhookDeliveryAttempt): boolean {
  return Boolean(attempt.sentPayload || attempt.enrichmentBlockUsed || (attempt.enrichmentResults && attempt.enrichmentResults.length > 0))
}

/** Detalhes de uma tentativa: payload efetivamente enviado + enriquecimento por chamada (com o erro bruto da rede). */
function AttemptDetails({ attempt }: { attempt: WebhookDeliveryAttempt }) {
  const results = attempt.enrichmentResults ?? []
  return (
    <Stack spacing={1} sx={{ p: 1 }}>
      <JsonBlock title="Payload enviado" value={attempt.sentPayload} emptyMessage="Payload enviado não registrado (entrega anterior a este recurso)." />
      <Box>
        <Typography variant="subtitle2">
          Enriquecimento{attempt.enrichmentBlockUsed ? ` (bloco usado: ${attempt.enrichmentBlockUsed})` : ''}
        </Typography>
        {results.length > 0 ? (
          <EnrichmentCallsTable calls={results} />
        ) : (
          <Typography variant="body2" color="text.secondary">
            Sem resultados de enriquecimento nesta tentativa.
          </Typography>
        )}
      </Box>
    </Stack>
  )
}

function DeliveryMeta({ delivery }: { delivery: WebhookDelivery }) {
  const hasMeta =
    delivery.enrichmentBlockMode || delivery.enrichmentBlockNumber !== undefined || delivery.modelStateAt || delivery.targetUrlOverride
  if (!hasMeta) return null
  return (
    <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap', mb: 1 }}>
      {(delivery.enrichmentBlockMode || delivery.enrichmentBlockNumber != null) && (
        <Chip
          size="small"
          variant="outlined"
          label={`Bloco do enriquecimento: ${blockModeLabel(delivery.enrichmentBlockMode)}${delivery.enrichmentBlockNumber != null ? ` (${delivery.enrichmentBlockNumber})` : ''}`}
        />
      )}
      {delivery.modelStateAt && (
        <Chip size="small" variant="outlined" label={`Modelo em: ${new Date(delivery.modelStateAt).toLocaleString('pt-BR')}`} />
      )}
      {delivery.targetUrlOverride && <Chip size="small" variant="outlined" color="warning" label={`URL de teste: ${delivery.targetUrlOverride}`} />}
    </Stack>
  )
}

function AttemptsTable({ delivery }: { delivery: WebhookDelivery }) {
  const [detailId, setDetailId] = useState<string | null>(null)
  if (delivery.attempts.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Nenhuma tentativa registrada ainda.
      </Typography>
    )
  }
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>#</TableCell>
          <TableCell>Quando</TableCell>
          <TableCell>Sucesso</TableCell>
          <TableCell>HTTP</TableCell>
          <TableCell>Resposta</TableCell>
          <TableCell>Erro</TableCell>
          <TableCell />
        </TableRow>
      </TableHead>
      <TableBody>
        {delivery.attempts.map((attempt) => (
          <Fragment key={attempt.id}>
            <TableRow>
              <TableCell>{attempt.attemptNumber}</TableCell>
              <TableCell>{new Date(attempt.attemptedAt).toLocaleString('pt-BR')}</TableCell>
              <TableCell>
                <Chip size="small" label={attempt.success ? 'sim' : 'não'} color={attempt.success ? 'success' : 'error'} />
              </TableCell>
              <TableCell>{attempt.httpStatus ?? '—'}</TableCell>
              <TableCell sx={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                <Tooltip title={attempt.responseBody ?? ''}>
                  <span>{attempt.responseBody ?? '—'}</span>
                </Tooltip>
              </TableCell>
              <TableCell sx={{ maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                <Tooltip title={attempt.errorMessage ?? ''}>
                  <span>{attempt.errorMessage ?? '—'}</span>
                </Tooltip>
              </TableCell>
              <TableCell align="right">
                {hasAttemptDetails(attempt) && (
                  <Button size="small" onClick={() => setDetailId(detailId === attempt.id ? null : attempt.id)}>
                    {detailId === attempt.id ? 'ocultar detalhes' : 'detalhes'}
                  </Button>
                )}
              </TableCell>
            </TableRow>
            {detailId === attempt.id && (
              <TableRow>
                <TableCell colSpan={7}>
                  <AttemptDetails attempt={attempt} />
                </TableCell>
              </TableRow>
            )}
          </Fragment>
        ))}
      </TableBody>
    </Table>
  )
}

/** Histórico de entregas do webhook (com origem, bloco do enriquecimento e detalhes por tentativa). */
export function WebhookDeliveriesSection({ webhookId }: { webhookId: string }) {
  const [page, setPage] = useState(0)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const deliveries = useWebhookDeliveries(webhookId, page, PAGE_SIZE)

  return (
    <Box id="webhook-deliveries" sx={{ mt: 5 }}>
      <Typography variant="h6">Entregas</Typography>

      {deliveries.isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Carregando...
        </Typography>
      )}
      {deliveries.isError && <ApiErrorAlert error={deliveries.error} />}

      {deliveries.data && deliveries.data.content.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Nenhuma entrega registrada ainda.
        </Typography>
      )}

      {deliveries.data && deliveries.data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 1 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Origem</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Tentativas</TableCell>
                <TableCell>Próxima tentativa</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {deliveries.data.content.map((delivery) => (
                <Fragment key={delivery.id}>
                  <TableRow hover>
                    <TableCell>
                      <OriginChip origin={delivery.origin} />
                    </TableCell>
                    <TableCell>{delivery.status}</TableCell>
                    <TableCell>{delivery.attemptCount}</TableCell>
                    <TableCell>
                      {delivery.nextAttemptAt ? new Date(delivery.nextAttemptAt).toLocaleString('pt-BR') : '—'}
                    </TableCell>
                    <TableCell align="right">
                      <Button size="small" onClick={() => setExpandedId(expandedId === delivery.id ? null : delivery.id)}>
                        {expandedId === delivery.id ? 'ocultar' : 'ver tentativas'}
                      </Button>
                    </TableCell>
                  </TableRow>
                  <TableRow>
                    <TableCell colSpan={5} sx={{ p: 0, borderBottom: expandedId === delivery.id ? undefined : 'none' }}>
                      <Collapse in={expandedId === delivery.id} unmountOnExit>
                        <Box sx={{ p: 2, bgcolor: 'action.hover' }}>
                          <DeliveryMeta delivery={delivery} />
                          <AttemptsTable delivery={delivery} />
                        </Box>
                      </Collapse>
                    </TableCell>
                  </TableRow>
                </Fragment>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {deliveries.data && (
        <Pagination
          page={deliveries.data.page}
          totalPages={deliveries.data.totalPages}
          totalElements={deliveries.data.totalElements}
          onPageChange={setPage}
        />
      )}
    </Box>
  )
}
