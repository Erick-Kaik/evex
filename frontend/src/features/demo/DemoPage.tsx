import { useState } from 'react'
import { Navigate } from 'react-router'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import {
  useActionRunner,
  useControlPanelActions,
  useControlPanelHealth,
} from './useControlPanel'
import { controlPanelUrl } from './controlPanel'
import type { ControlPanelAction } from './controlPanel'

/**
 * Aba "Demonstração" (E17/DEC-075) — dispara os scripts do `local-env/` via `local-env/control-panel`.
 * **Não faz parte do produto:** só é montada quando `VITE_LOCALENV_CONTROL_URL` está setada e o
 * serviço respondeu ao health check (ver `router.tsx` / `RootLayout.tsx`). Este componente ainda
 * revalida a disponibilidade e redireciona se o serviço tiver caído no meio do caminho.
 */
export function DemoPage() {
  const health = useControlPanelHealth()
  const actions = useControlPanelActions()

  if (!controlPanelUrl()) {
    return <Navigate to="/contracts" replace />
  }
  if (health.isPending) {
    return (
      <Box sx={{ p: 3, display: 'flex', alignItems: 'center', gap: 1 }}>
        <CircularProgress size={18} />
        <Typography variant="body2" color="text.secondary">
          Verificando o control-panel local...
        </Typography>
      </Box>
    )
  }
  if (health.isError || health.data?.ok !== true) {
    return <Navigate to="/contracts" replace />
  }

  return (
    <Box sx={{ p: 3, maxWidth: 900 }}>
      <Typography variant="h5" sx={{ fontWeight: 600 }}>
        Demonstração
      </Typography>
      <Alert severity="info" sx={{ mt: 2 }}>
        Ferramenta de ambiente local — dispara os scripts do <code>local-env/</code> (deploy dos
        contratos de teste, emissão de eventos, registro no Evex). <strong>Não faz parte do
        produto</strong> e só aparece quando o <code>control-panel</code> local está no ar.
      </Alert>

      {actions.isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
          Carregando ações...
        </Typography>
      )}
      {actions.isError && <ApiErrorAlert error={actions.error} />}

      <Stack sx={{ gap: 2, mt: 3 }}>
        {actions.data?.map((action) => (
          <ActionCard key={action.id} action={action} />
        ))}
      </Stack>
    </Box>
  )
}

function ActionCard({ action }: { action: ControlPanelAction }) {
  const runner = useActionRunner()
  const [params, setParams] = useState<Record<string, number>>(() =>
    Object.fromEntries(action.params.map((param) => [param.name, param.default])),
  )

  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="h6">{action.label}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          {action.description}
        </Typography>

        <Box
          component="pre"
          sx={{
            mt: 1.5,
            p: 1.5,
            bgcolor: 'grey.100',
            borderRadius: 1,
            fontFamily: 'monospace',
            fontSize: '0.8rem',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            m: 0,
          }}
        >
          {action.commandTemplate}
        </Box>

        {action.params.length > 0 && (
          <Stack direction="row" sx={{ gap: 2, mt: 2, flexWrap: 'wrap' }}>
            {action.params.map((param) => (
              <TextField
                key={param.name}
                label={param.label}
                type="number"
                size="small"
                value={params[param.name]}
                onChange={(event) =>
                  setParams((current) => ({
                    ...current,
                    [param.name]: Number(event.target.value),
                  }))
                }
                sx={{ maxWidth: 160 }}
              />
            ))}
          </Stack>
        )}

        <Stack direction="row" sx={{ alignItems: 'center', gap: 1.5, mt: 2 }}>
          <Button
            variant="contained"
            startIcon={
              runner.state === 'running' ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />
            }
            disabled={runner.state === 'running'}
            onClick={() => runner.run(action, params)}
          >
            {runner.state === 'running' ? 'Executando...' : 'Executar'}
          </Button>
          <RunStatusChip state={runner.state} exitCode={runner.exitCode} error={runner.error} />
        </Stack>

        {runner.error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {runner.error}
          </Alert>
        )}

        {runner.lines.length > 0 && (
          <Box
            component="pre"
            sx={{
              mt: 2,
              p: 1.5,
              bgcolor: 'grey.900',
              color: 'grey.100',
              borderRadius: 1,
              fontFamily: 'monospace',
              fontSize: '0.75rem',
              maxHeight: 320,
              overflow: 'auto',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              m: 0,
            }}
          >
            {runner.lines.join('\n')}
          </Box>
        )}
      </CardContent>
    </Card>
  )
}

function RunStatusChip({
  state,
  exitCode,
  error,
}: {
  state: 'idle' | 'running' | 'done'
  exitCode: number | undefined
  error: string | undefined
}) {
  if (state === 'idle') {
    return null
  }
  if (state === 'running') {
    return <Chip size="small" color="info" label="rodando" />
  }
  if (error) {
    return <Chip size="small" color="error" label="falhou" />
  }
  if (exitCode === 0) {
    return <Chip size="small" color="success" label="exit 0" />
  }
  return <Chip size="small" color="error" label={`exit ${exitCode ?? '?'}`} />
}
