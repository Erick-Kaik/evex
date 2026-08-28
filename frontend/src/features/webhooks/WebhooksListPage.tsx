import { useState } from 'react'
import { useNavigate } from 'react-router'
import {
  Box,
  Chip,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { Pagination } from '../../components/Pagination'
import { useCreateWebhook, useWebhooks } from './useWebhooks'
import { WebhookForm } from './WebhookForm'

const PAGE_SIZE = 20

/** Listagem de webhooks configurados + formulário de cadastro (doc §4.6). */
export function WebhooksListPage() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const { data, isPending, isError, error } = useWebhooks(page, PAGE_SIZE)
  const createWebhook = useCreateWebhook()

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600 }}>
        Webhooks
      </Typography>

      {isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Carregando...
        </Typography>
      )}
      {isError && <ApiErrorAlert error={error} />}

      {data && data.content.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Nenhum webhook cadastrado ainda.
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
                <TableCell>Ativo</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((webhook) => (
                <TableRow key={webhook.id} hover onClick={() => navigate(`/webhooks/${webhook.id}`)} sx={{ cursor: 'pointer' }}>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{webhook.contractAddress}</TableCell>
                  <TableCell>{webhook.eventName}</TableCell>
                  <TableCell>{webhook.url}</TableCell>
                  <TableCell>
                    <Chip size="small" label={webhook.active ? 'sim' : 'não'} color={webhook.active ? 'success' : 'default'} />
                  </TableCell>
                </TableRow>
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

      <Box sx={{ mt: 5 }}>
        <Typography variant="h6">Novo webhook</Typography>
        <Box sx={{ mt: 2 }}>
          <WebhookForm
            submitLabel="Cadastrar"
            isPending={createWebhook.isPending}
            isError={createWebhook.isError}
            error={createWebhook.error}
            isSuccess={createWebhook.isSuccess}
            successMessage="Webhook cadastrado com sucesso."
            onSubmit={(payload) => createWebhook.mutate(payload)}
          />
        </Box>
      </Box>
    </Box>
  )
}
