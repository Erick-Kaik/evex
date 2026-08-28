import { useState } from 'react'
import type { FormEvent } from 'react'
import {
  Box,
  Button,
  MenuItem,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { BalanceBehavior } from '../../api/api'
import type { BalanceMonitorConfig, Network } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { Pagination } from '../../components/Pagination'
import {
  useBalanceMonitors,
  useCreateBalanceMonitor,
  useUpdateBalanceMonitorStatus,
} from './useBalanceMonitors'

const PAGE_SIZE = 20

/**
 * Configurações de monitoramento de saldo (`BalanceMonitorConfig`, doc §4.4) — tabela + form de
 * criação com campos condicionais por `behavior`, mesma regra de validação que o backend já aplica
 * (`CreateBalanceMonitorPayload`), replicada aqui só pra dar feedback antes do submit.
 */
export function BalanceMonitorSection() {
  const [page, setPage] = useState(0)
  const monitors = useBalanceMonitors(page, PAGE_SIZE)
  const createMonitor = useCreateBalanceMonitor()

  const [contractAddress, setContractAddress] = useState('')
  const [network, setNetwork] = useState<Network | ''>('')
  const [eventName, setEventName] = useState('')
  const [behavior, setBehavior] = useState<BalanceBehavior>(BalanceBehavior.Credit)
  const [walletParam, setWalletParam] = useState('')
  const [fromParam, setFromParam] = useState('')
  const [toParam, setToParam] = useState('')
  const [valueParam, setValueParam] = useState('')
  const [tokenIdParam, setTokenIdParam] = useState('')

  const isTransfer = behavior === BalanceBehavior.Transfer

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (network === '') {
      return
    }

    createMonitor.mutate(
      {
        contractAddress,
        network,
        eventName,
        behavior,
        walletParam: isTransfer ? undefined : walletParam,
        fromParam: isTransfer ? fromParam : undefined,
        toParam: isTransfer ? toParam : undefined,
        valueParam,
        tokenIdParam: tokenIdParam || undefined,
      },
      {
        onSuccess: () => {
          setContractAddress('')
          setEventName('')
          setWalletParam('')
          setFromParam('')
          setToParam('')
          setValueParam('')
          setTokenIdParam('')
        },
      },
    )
  }

  return (
    <Box component="section" sx={{ mt: 5 }}>
      <Typography variant="h6">Configurações de monitoramento</Typography>

      {monitors.isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Carregando...
        </Typography>
      )}
      {monitors.isError && <ApiErrorAlert error={monitors.error} />}

      {monitors.data && monitors.data.content.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Nenhuma configuração cadastrada ainda.
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
                <TableCell>Ativa</TableCell>
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

      <Box component="form" onSubmit={handleSubmit} sx={{ mt: 3, display: 'flex', flexDirection: 'column', gap: 2, maxWidth: 480 }}>
        <Typography variant="subtitle2">Nova configuração</Typography>

        <TextField
          label="Contrato"
          required
          fullWidth
          value={contractAddress}
          onChange={(event) => setContractAddress(event.target.value)}
          placeholder="0x..."
          slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
        />

        <NetworkSelect required value={network} onChange={setNetwork} />

        <TextField
          label="Nome do evento"
          required
          fullWidth
          value={eventName}
          onChange={(event) => setEventName(event.target.value)}
          placeholder="ex.: Transfer"
        />

        <TextField
          select
          label="Comportamento"
          fullWidth
          value={behavior}
          onChange={(event) => setBehavior(event.target.value as BalanceBehavior)}
        >
          <MenuItem value={BalanceBehavior.Credit}>CREDIT</MenuItem>
          <MenuItem value={BalanceBehavior.Debit}>DEBIT</MenuItem>
          <MenuItem value={BalanceBehavior.Delta}>DELTA</MenuItem>
          <MenuItem value={BalanceBehavior.Transfer}>TRANSFER</MenuItem>
        </TextField>

        {isTransfer ? (
          <>
            <TextField
              label="Parâmetro de origem (from)"
              required
              fullWidth
              value={fromParam}
              onChange={(event) => setFromParam(event.target.value)}
            />
            <TextField
              label="Parâmetro de destino (to)"
              required
              fullWidth
              value={toParam}
              onChange={(event) => setToParam(event.target.value)}
            />
          </>
        ) : (
          <TextField
            label="Parâmetro da carteira"
            required
            fullWidth
            value={walletParam}
            onChange={(event) => setWalletParam(event.target.value)}
          />
        )}

        <TextField
          label="Parâmetro do montante/delta"
          required
          fullWidth
          value={valueParam}
          onChange={(event) => setValueParam(event.target.value)}
        />

        <TextField
          label="Parâmetro do tokenId (opcional)"
          fullWidth
          value={tokenIdParam}
          onChange={(event) => setTokenIdParam(event.target.value)}
        />

        {createMonitor.isError && <ApiErrorAlert error={createMonitor.error} />}
        {createMonitor.isSuccess && (
          <Typography variant="body2" color="success.main">
            Configuração cadastrada com sucesso.
          </Typography>
        )}

        <Button type="submit" variant="contained" disabled={createMonitor.isPending} sx={{ alignSelf: 'flex-start' }}>
          {createMonitor.isPending ? 'Cadastrando...' : 'Cadastrar'}
        </Button>
      </Box>
    </Box>
  )
}

function MonitorRow({ config }: { config: BalanceMonitorConfig }) {
  const updateStatus = useUpdateBalanceMonitorStatus(config.id)

  return (
    <TableRow>
      <TableCell sx={{ fontFamily: 'monospace' }}>{config.contractAddress}</TableCell>
      <TableCell>{config.eventName}</TableCell>
      <TableCell>{config.behavior}</TableCell>
      <TableCell>{config.active ? 'sim' : 'não'}</TableCell>
      <TableCell>
        <Button size="small" disabled={updateStatus.isPending} onClick={() => updateStatus.mutate({ active: !config.active })}>
          {config.active ? 'Pausar' : 'Ativar'}
        </Button>
      </TableCell>
    </TableRow>
  )
}
