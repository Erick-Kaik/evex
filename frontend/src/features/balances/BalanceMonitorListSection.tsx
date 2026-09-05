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
import type { BalanceMonitorConfig } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { Pagination } from '../../components/Pagination'
import { RowActions } from '../../components/RowActions'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import { useBalanceMonitors, useUpdateBalanceMonitorStatus } from './useBalanceMonitors'

const PAGE_SIZE = 20

const ACTIVE_OPTIONS: StatusOption<boolean>[] = [
  { value: true, label: 'Ativa', color: 'success' },
  { value: false, label: 'Pausada', color: 'default' },
]

/**
 * Listagem de configurações de monitoramento de saldo (`BalanceMonitorConfig`, doc §4.4) — status
 * como toggle rápido e ações "Ver"/"Editar" por linha (padrão E18). Cadastro em página dedicada
 * (`/balances/monitors/new`), não mais em formulário inline dentro da seção (supera a versão anterior
 * desta tela — ver DEC-079). O reprocessamento de histórico (backfill) mudou para o detalhe de cada
 * configuração — a listagem fica só com o essencial (contrato/evento/comportamento/status/ações).
 */
export function BalanceMonitorListSection() {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const monitors = useBalanceMonitors(page, PAGE_SIZE)

  return (
    <Box component="section" sx={{ mt: 5 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h6">Configurações de monitoramento</Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => navigate('/balances/monitors/new')}
        >
          Cadastrar
        </Button>
      </Stack>

      {monitors.isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Carregando...
        </Typography>
      )}
      {monitors.isError && <ApiErrorAlert error={monitors.error} />}

      {monitors.data && monitors.data.content.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Nenhuma configuração cadastrada ainda. Use "Cadastrar" para criar a primeira.
        </Typography>
      )}

      {monitors.data && monitors.data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 1 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Contrato</TableCell>
                <TableCell>Evento</TableCell>
                <TableCell>Comportamento</TableCell>
                <TableCell>Status</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {monitors.data.content.map((config) => (
                <MonitorRow key={config.id} config={config} />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {monitors.data && (
        <Pagination
          page={monitors.data.page}
          totalPages={monitors.data.totalPages}
          totalElements={monitors.data.totalElements}
          onPageChange={setPage}
        />
      )}
    </Box>
  )
}

function MonitorRow({ config }: { config: BalanceMonitorConfig }) {
  const navigate = useNavigate()
  const updateStatus = useUpdateBalanceMonitorStatus(config.id)

  return (
    <TableRow hover>
      <TableCell sx={{ fontFamily: 'monospace' }}>{config.contractAddress}</TableCell>
      <TableCell>{config.eventName}</TableCell>
      <TableCell>{config.behavior}</TableCell>
      <TableCell>
        <StatusToggle
          value={config.active}
          options={ACTIVE_OPTIONS}
          disabled={updateStatus.isPending}
          onChange={(active) => updateStatus.mutate({ active })}
        />
      </TableCell>
      <TableCell align="right">
        <RowActions
          onView={() => navigate(`/balances/monitors/${config.id}`)}
          onEdit={() => navigate(`/balances/monitors/${config.id}/edit`)}
        />
      </TableCell>
    </TableRow>
  )
}
