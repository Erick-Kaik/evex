import { Fragment, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
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
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { Pagination } from '../../components/Pagination'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import type { PageMode } from '../../components/pageMode'
import {
  useCreateWebhook,
  useUpdateWebhook,
  useUpdateWebhookStatus,
  useWebhook,
  useWebhookDeliveries,
} from './useWebhooks'
import { WebhookForm } from './WebhookForm'
import type { WebhookFormValues } from './WebhookForm'

const PAGE_SIZE = 20

const ACTIVE_OPTIONS: StatusOption<boolean>[] = [
  { value: true, label: 'Ativo', color: 'success' },
  { value: false, label: 'Pausado', color: 'default' },
]

interface WebhookFormPageProps {
  mode: PageMode
}

/**
 * Página de formulário de webhook, nos 3 modos do padrão E18 (mesmo componente para os 3, ver
 * DEC-079): `create` (`/webhooks/new`), `view` (`/webhooks/:id`, campos só leitura + log de
 * entregas) e `edit` (`/webhooks/:id/edit`). O toggle rápido de status e a tabela de entregas só
 * aparecem em `view`/`edit`, quando já existe um webhook cadastrado.
 */
export function WebhookFormPage({ mode }: WebhookFormPageProps) {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  if (mode === 'create') {
    return <CreateForm />
  }

  if (!id) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Identificador do webhook ausente na URL.')} />
      </Box>
    )
  }

  return <ViewEditForm id={id} mode={mode} navigateBack={() => navigate('/webhooks')} />
}

function CreateForm() {
  const navigate = useNavigate()
  const createWebhook = useCreateWebhook()

  function handleSubmit(payload: WebhookFormValues) {
    createWebhook.mutate(payload, {
      onSuccess: (webhook) => navigate(`/webhooks/${webhook.id}`),
    })
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/webhooks')} sx={{ mb: 2 }}>
        Voltar
      </Button>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 3 }}>
        Novo webhook
      </Typography>

      <WebhookForm
        mode="create"
        submitLabel="Cadastrar"
        isPending={createWebhook.isPending}
        isError={createWebhook.isError}
        error={createWebhook.error}
        onSubmit={handleSubmit}
      />
    </Box>
  )
}

function ViewEditForm({ id, mode, navigateBack }: { id: string; mode: PageMode; navigateBack: () => void }) {
  const navigate = useNavigate()
  const webhook = useWebhook(id)
  const updateWebhook = useUpdateWebhook(id)
  const updateStatus = useUpdateWebhookStatus(id)

  const [page, setPage] = useState(0)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const deliveries = useWebhookDeliveries(id, page, PAGE_SIZE)

  function handleSubmit(payload: WebhookFormValues) {
    updateWebhook.mutate(payload, {
      onSuccess: () => navigate(`/webhooks/${id}`),
    })
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={navigateBack} sx={{ mb: 2 }}>
        Voltar
      </Button>

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
              <StatusToggle
                value={webhook.data.active}
                options={ACTIVE_OPTIONS}
                disabled={updateStatus.isPending}
                onChange={(active) => updateStatus.mutate({ active })}
              />
              {mode === 'view' && (
                <Button
                  variant="outlined"
                  size="small"
                  startIcon={<EditIcon />}
                  onClick={() => navigate(`/webhooks/${id}/edit`)}
                >
                  Editar
                </Button>
              )}
            </Stack>
          </Stack>
          {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}

          <Box sx={{ mt: 3 }}>
            <WebhookForm
              key={webhook.data.id}
              mode={mode}
              initialValues={webhook.data}
              submitLabel="Salvar"
              isPending={updateWebhook.isPending}
              isError={updateWebhook.isError}
              error={updateWebhook.error}
              onSubmit={handleSubmit}
            />
          </Box>

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
