import { Fragment, useState } from 'react'
import { useParams } from 'react-router'
import {
  Box,
  Button,
  Card,
  CardContent,
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
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { Pagination } from '../../components/Pagination'
import {
  useUpdateWebhook,
  useUpdateWebhookStatus,
  useWebhook,
  useWebhookDeliveries,
} from './useWebhooks'
import { WebhookForm } from './WebhookForm'

const PAGE_SIZE = 20

/**
 * Detalhe de um webhook: config + toggle de status + tabela de entregas com o log de tentativas
 * (status/attemptCount/nextAttemptAt, expansível pra ver cada `WebhookDeliveryAttempt` — doc §4.6).
 */
export function WebhookDetailPage() {
  const { id } = useParams<{ id: string }>()
  const webhook = useWebhook(id)
  const updateWebhook = useUpdateWebhook(id ?? '')
  const updateStatus = useUpdateWebhookStatus(id ?? '')

  const [page, setPage] = useState(0)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const deliveries = useWebhookDeliveries(id, page, PAGE_SIZE)

  if (!id) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Identificador do webhook ausente na URL.')} />
      </Box>
    )
  }

  return (
    <Box sx={{ p: 3 }}>
      {webhook.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando...
        </Typography>
      )}
      {webhook.isError && <ApiErrorAlert error={webhook.error} />}

      {webhook.data && (
        <>
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
            <Box>
              <Typography variant="h5" sx={{ fontWeight: 600 }}>
                Webhook
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {webhook.data.contractAddress} · {webhook.data.network} · {webhook.data.eventName}
              </Typography>
            </Box>
            <Stack direction="row" sx={{ alignItems: 'center', gap: 1 }}>
              <Chip size="small" label={webhook.data.active ? 'ativo' : 'pausado'} color={webhook.data.active ? 'success' : 'default'} />
              <Button
                variant="outlined"
                size="small"
                disabled={updateStatus.isPending}
                onClick={() => updateStatus.mutate({ active: !webhook.data.active })}
              >
                {webhook.data.active ? 'Pausar' : 'Ativar'}
              </Button>
            </Stack>
          </Stack>
          {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}

          <Card variant="outlined" sx={{ mt: 3 }}>
            <CardContent>
              <Typography variant="subtitle2" gutterBottom>
                Editar configuração
              </Typography>
              <WebhookForm
                key={webhook.data.id}
                initialValues={webhook.data}
                submitLabel="Salvar"
                isPending={updateWebhook.isPending}
                isError={updateWebhook.isError}
                error={updateWebhook.error}
                isSuccess={updateWebhook.isSuccess}
                successMessage="Webhook atualizado com sucesso."
                onSubmit={(payload) => updateWebhook.mutate(payload)}
              />
            </CardContent>
          </Card>

          <Box sx={{ mt: 5 }}>
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
                          <TableCell colSpan={4} sx={{ p: 0, borderBottom: expandedId === delivery.id ? undefined : 'none' }}>
                            <Collapse in={expandedId === delivery.id} unmountOnExit>
                              <Box sx={{ p: 2, bgcolor: 'action.hover' }}>
                                {delivery.attempts.length === 0 && (
                                  <Typography variant="body2" color="text.secondary">
                                    Nenhuma tentativa registrada ainda.
                                  </Typography>
                                )}
                                {delivery.attempts.length > 0 && (
                                  <Table size="small">
                                    <TableHead>
                                      <TableRow>
                                        <TableCell>#</TableCell>
                                        <TableCell>Quando</TableCell>
                                        <TableCell>Sucesso</TableCell>
                                        <TableCell>HTTP</TableCell>
                                        <TableCell>Resposta</TableCell>
                                        <TableCell>Erro</TableCell>
                                      </TableRow>
                                    </TableHead>
                                    <TableBody>
                                      {delivery.attempts.map((attempt) => (
                                        <TableRow key={attempt.id}>
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
                                        </TableRow>
                                      ))}
                                    </TableBody>
                                  </Table>
                                )}
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
        </>
      )}
    </Box>
  )
}
