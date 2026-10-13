import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import {
  Box,
  Button,
  Stack,
  Typography,
} from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import PreviewIcon from '@mui/icons-material/Visibility'
import SendIcon from '@mui/icons-material/Send'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import type { PageMode } from '../../components/pageMode'
import {
  useCreateWebhook,
  useUpdateWebhook,
  useUpdateWebhookStatus,
  useWebhook,
} from './useWebhooks'
import { WebhookDeliveriesSection } from './WebhookDeliveriesSection'
import { WebhookResendDialog } from './WebhookResendDialog'
import { WebhookForm } from './WebhookForm'
import type { WebhookFormValues } from './WebhookForm'

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

  const [dialogIntent, setDialogIntent] = useState<'preview' | 'resend' | null>(null)

  function handleViewDeliveries() {
    setDialogIntent(null)
    document.getElementById('webhook-deliveries')?.scrollIntoView?.({ behavior: 'smooth' })
  }

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
                <>
                  <Button variant="outlined" size="small" startIcon={<PreviewIcon />} onClick={() => setDialogIntent('preview')}>
                    Pré-visualizar
                  </Button>
                  <Button variant="outlined" size="small" startIcon={<SendIcon />} onClick={() => setDialogIntent('resend')}>
                    Reenviar
                  </Button>
                </>
              )}
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

          <WebhookDeliveriesSection webhookId={id} />

          <WebhookResendDialog
            webhookId={id}
            webhookActive={webhook.data.active}
            open={dialogIntent !== null}
            intent={dialogIntent ?? 'preview'}
            onClose={() => setDialogIntent(null)}
            onViewDeliveries={handleViewDeliveries}
          />
        </>
      )}
    </Box>
  )
}
