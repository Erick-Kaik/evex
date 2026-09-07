import { useState } from 'react'
import { useNavigate } from 'react-router'
import {
  Box,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import AddIcon from '@mui/icons-material/Add'
import Button from '@mui/material/Button'
import type { WebhookConfig } from '../../api/api'
import { AddressCell } from '../../components/AddressCell'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { Pagination } from '../../components/Pagination'
import { RowActions } from '../../components/RowActions'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import { useUpdateWebhookStatus, useWebhooks } from './useWebhooks'

const PAGE_SIZE = 20

const ACTIVE_OPTIONS: StatusOption<boolean>[] = [
  { value: true, label: 'Ativo', color: 'success' },
  { value: false, label: 'Pausado', color: 'default' },
]

/**
 * Listagem de webhooks configurados (doc §4.6) — status como toggle rápido e ações "Ver"/"Editar" por
 * linha (padrão E18). Cadastro em página dedicada (`/webhooks/new`), não mais inline nesta tela.
 */
export function WebhooksListPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const { data, isPending, isError, error } = useWebhooks(page, PAGE_SIZE)

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>
          Webhooks
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/webhooks/new')}>
          Cadastrar
        </Button>
      </Stack>

      {isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Carregando...
        </Typography>
      )}
      {isError && <ApiErrorAlert error={error} />}

      {data && data.content.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Nenhum webhook cadastrado ainda. Use "Cadastrar" para criar o primeiro.
        </Typography>
      )}

      {data && data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Contrato</TableCell>
                <TableCell>Evento</TableCell>
                <TableCell>URL</TableCell>
                <TableCell>Status</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((webhook) => (
                <WebhookRow key={webhook.id} webhook={webhook} />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {data && (
        <Pagination
          page={data.page}
          totalPages={data.totalPages}
          totalElements={data.totalElements}
          onPageChange={setPage}
        />
      )}
    </Box>
  )
}

function WebhookRow({ webhook }: { webhook: WebhookConfig }) {
  const navigate = useNavigate()
  const updateStatus = useUpdateWebhookStatus(webhook.id)

  return (
    <TableRow hover>
      <TableCell>
        <AddressCell address={webhook.contractAddress} kind="contract" />
      </TableCell>
      <TableCell>{webhook.eventName}</TableCell>
      <TableCell>{webhook.url}</TableCell>
      <TableCell>
        <StatusToggle
          value={webhook.active}
          options={ACTIVE_OPTIONS}
          disabled={updateStatus.isPending}
          onChange={(active) => updateStatus.mutate({ active })}
        />
      </TableCell>
      <TableCell align="right">
        <RowActions
          onView={() => navigate(`/webhooks/${webhook.id}`)}
          onEdit={() => navigate(`/webhooks/${webhook.id}/edit`)}
        />
      </TableCell>
    </TableRow>
  )
}
