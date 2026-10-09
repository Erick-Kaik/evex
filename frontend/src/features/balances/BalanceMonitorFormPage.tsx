import { useLocation, useNavigate, useParams } from 'react-router'
import { Alert, Box, Button, Card, Chip, CardContent, LinearProgress, Stack, Tooltip, Typography } from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import EditIcon from '@mui/icons-material/Edit'
import HistoryIcon from '@mui/icons-material/History'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import type { PageMode } from '../../components/pageMode'
import { BalanceMonitorForm } from './BalanceMonitorForm'
import type { BalanceMonitorFormValues } from './BalanceMonitorForm'
import {
  useBalanceBackfill,
  useBalanceMonitor,
  useCreateBalanceMonitor,
  useGlobalBalanceBackfillStatus,
  useUpdateBalanceMonitor,
  useUpdateBalanceMonitorStatus,
} from './useBalanceMonitors'

const ACTIVE_OPTIONS: StatusOption<boolean>[] = [
  { value: true, label: 'Ativa', color: 'success' },
  { value: false, label: 'Pausada', color: 'default' },
]

interface BalanceMonitorFormPageProps {
  mode: PageMode
}

/**
 * Página de formulário de configuração de monitoramento de saldo, nos 3 modos do padrão E18
 * (mesmo componente para os 3, ver DEC-079): `create` (`/balances/monitors/new`), `view`
 * (`/balances/monitors/:id`, campos só leitura) e `edit` (`/balances/monitors/:id/edit`). O toggle
 * rápido de status e o card de reprocessamento de histórico (backfill) só aparecem em `view`/`edit`.
 */
export function BalanceMonitorFormPage({ mode }: BalanceMonitorFormPageProps) {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  if (mode === 'create') {
    return <CreateForm />
  }

  if (!id) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Identificador da configuração ausente na URL.')} />
      </Box>
    )
  }

  return <ViewEditForm id={id} mode={mode} navigateBack={() => navigate('/balances')} />
}

function CreateForm() {
  const navigate = useNavigate()
  const createMonitor = useCreateBalanceMonitor()

  function handleSubmit(payload: BalanceMonitorFormValues) {
    createMonitor.mutate(payload, {
      // `warnings` (DEC-110) só vêm na resposta do POST/PUT: segue para o detalhe via `state` da navegação.
      onSuccess: (config) =>
        navigate(`/balances/monitors/${config.id}`, { state: { warnings: config.warnings ?? [] } }),
    })
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/balances')} sx={{ mb: 2 }}>
        Voltar
      </Button>
      <Typography variant="h5" sx={{ fontWeight: 600, mb: 3 }}>
        Nova configuração de monitoramento
      </Typography>

      <BalanceMonitorForm
        mode="create"
        submitLabel="Cadastrar"
        isPending={createMonitor.isPending}
        isError={createMonitor.isError}
        error={createMonitor.error}
        onSubmit={handleSubmit}
      />
    </Box>
  )
}

/** Lê `warnings` do `state` da navegação pós-create/update, tolerando qualquer outro formato. */
function readWarnings(state: unknown): string[] {
  if (typeof state !== 'object' || state === null || !('warnings' in state)) return []
  const { warnings } = state as { warnings: unknown }
  return Array.isArray(warnings) ? warnings.filter((w): w is string => typeof w === 'string') : []
}

function ViewEditForm({ id, mode, navigateBack }: { id: string; mode: PageMode; navigateBack: () => void }) {
  const navigate = useNavigate()
  const warnings = readWarnings(useLocation().state)
  const monitor = useBalanceMonitor(id)
  const updateMonitor = useUpdateBalanceMonitor(id)
  const updateStatus = useUpdateBalanceMonitorStatus(id)

  function handleSubmit(payload: BalanceMonitorFormValues) {
    updateMonitor.mutate(payload, {
      onSuccess: (config) => navigate(`/balances/monitors/${id}`, { state: { warnings: config.warnings ?? [] } }),
    })
  }

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={navigateBack} sx={{ mb: 2 }}>
        Voltar
      </Button>

      {monitor.isPending && (
        <Typography variant="body2" color="text.secondary">
          Carregando...
        </Typography>
      )}
      {monitor.isError && <ApiErrorAlert error={monitor.error} />}

      {monitor.data && (
        <>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 2 }}
          >
            <Stack direction="row" sx={{ alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
              <Typography variant="h5" sx={{ fontWeight: 600 }}>
                Configuração de saldo
              </Typography>
              {monitor.data.batch && <Chip size="small" color="info" label="Lote" />}
              <StatusToggle
                value={monitor.data.active}
                options={ACTIVE_OPTIONS}
                disabled={updateStatus.isPending}
                onChange={(active) => updateStatus.mutate({ active })}
              />
            </Stack>
            {mode === 'view' && (
              <Button
                variant="outlined"
                startIcon={<EditIcon />}
                onClick={() => navigate(`/balances/monitors/${id}/edit`)}
              >
                Editar
              </Button>
            )}
          </Stack>
          {updateStatus.isError && <ApiErrorAlert error={updateStatus.error} />}
          {warnings.length > 0 && (
            <Alert severity="warning" sx={{ mt: 2 }}>
              <Typography variant="subtitle2">Configuração salva com avisos</Typography>
              {warnings.map((warning) => (
                <Typography key={warning} variant="body2">
                  {warning}
                </Typography>
              ))}
            </Alert>
          )}

          <Box sx={{ mt: 3 }}>
            <BalanceMonitorForm
              key={monitor.data.id}
              mode={mode}
              initialValues={monitor.data}
              submitLabel="Salvar"
              isPending={updateMonitor.isPending}
              isError={updateMonitor.isError}
              error={updateMonitor.error}
              onSubmit={handleSubmit}
            />
          </Box>

          <BackfillCard configId={monitor.data.id} />
        </>
      )}
    </Box>
  )
}

function BackfillCard({ configId }: { configId: string }) {
  const backfill = useBalanceBackfill(configId)
  const backfillStatus = useGlobalBalanceBackfillStatus()
  const status = backfillStatus.data

  // Status global (DEC-068): `configId` diz qual config está reprojetando agora. `thisRunning` só
  // quando é esta configuração; `otherRunning` quando é outra — o botão desta fica desabilitado.
  const thisRunning = status?.running === true && status.configId === configId
  const otherRunning = status?.running === true && status.configId != null && status.configId !== configId
  const finishedThisConfig = backfill.isSuccess && !thisRunning && !otherRunning

  const disabled = backfill.isPending || thisRunning || otherRunning
  const tooltip = otherRunning
    ? 'Há um reprocessamento de outra configuração em andamento — aguarde ele terminar'
    : thisRunning
      ? 'Reprocessamento desta configuração em andamento'
      : 'Reprojeta os eventos já indexados e decodificados contra esta configuração, sem reconsultar a rede'

  const total = status ? status.processedCount + status.remainingCount : 0
  const progressValue =
    thisRunning && status && total > 0 ? Math.min(100, (status.processedCount / total) * 100) : undefined

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Reprocessamento de histórico
        </Typography>
        <Stack sx={{ gap: 1 }}>
          <Tooltip title={tooltip}>
            <span>
              <Button
                size="small"
                variant="outlined"
                startIcon={<HistoryIcon />}
                disabled={disabled}
                onClick={() => backfill.mutate()}
                sx={{ alignSelf: 'flex-start' }}
              >
                Reprocessar histórico
              </Button>
            </span>
          </Tooltip>

          {backfill.isError && <ApiErrorAlert error={backfill.error} />}

          {otherRunning && (
            <Typography variant="caption" color="text.secondary">
              Reprocessamento de outra configuração em andamento.
            </Typography>
          )}

          {thisRunning && status && (
            <>
              <LinearProgress
                variant={progressValue === undefined ? 'indeterminate' : 'determinate'}
                value={progressValue}
              />
              <Typography variant="caption" color="text.secondary">
                processados {status.processedCount} · deltas {status.deltaCount} · restam{' '}
                {status.remainingCount}
              </Typography>
            </>
          )}

          {finishedThisConfig && (
            <Typography variant="caption" color="success.main">
              Reprocessamento concluído.
            </Typography>
          )}
        </Stack>
      </CardContent>
    </Card>
  )
}
