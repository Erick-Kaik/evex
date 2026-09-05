import { useState } from 'react'
import type { FormEvent } from 'react'
import {
  Box,
  Button,
  FormControlLabel,
  Link,
  Paper,
  Radio,
  RadioGroup,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import type { AssetBalance, AssetBalanceSnapshot, Network } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { Pagination } from '../../components/Pagination'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import type { BalanceFilters } from './useBalances'
import { useBalanceHistory, useBalances } from './useBalances'

const PAGE_SIZE = 20

type Mode = 'current' | 'history'

function isSnapshot(row: AssetBalance | AssetBalanceSnapshot): row is AssetBalanceSnapshot {
  return 'snapshotDate' in row
}

/**
 * Consulta de saldos — atual ou consolidado numa data específica (doc §4.4). `balance` é sempre
 * exibido como veio da API (string decimal) — nunca convertido para `number`, cuja precisão segura
 * (`Number.MAX_SAFE_INTEGER`) é excedida por inteiros de 256 bits (uint256/int256).
 */
export function BalanceQuerySection() {
  const [mode, setMode] = useState<Mode>('current')
  const [walletAddress, setWalletAddress] = useState('')
  const [contractAddress, setContractAddress] = useState('')
  const [contractRef, setContractRef] = useState<ContractRef | null>(null)
  // Por padrão o contrato vem da lista de monitorados; `manualContract` libera digitar um endereço
  // não cadastrado (e, aí sim, escolher a rede à mão).
  const [manualContract, setManualContract] = useState(false)
  const [tokenId, setTokenId] = useState('')
  const [network, setNetwork] = useState<Network | ''>('')
  const [date, setDate] = useState('')

  const effectiveContractAddress = manualContract ? contractAddress : (contractRef?.address ?? '')
  const effectiveNetwork: Network | '' = manualContract ? network : (contractRef?.network ?? '')

  const [filters, setFilters] = useState<BalanceFilters>({})
  const [appliedDate, setAppliedDate] = useState<string | undefined>(undefined)
  const [page, setPage] = useState(0)

  const currentQuery = useBalances(filters, page, PAGE_SIZE)
  const historyQuery = useBalanceHistory(appliedDate, filters, page, PAGE_SIZE)

  const { data, isPending, isError, error } = mode === 'current' ? currentQuery : historyQuery

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFilters({
      walletAddress: walletAddress || undefined,
      contractAddress: effectiveContractAddress || undefined,
      tokenId: tokenId || undefined,
      network: effectiveNetwork === '' ? undefined : effectiveNetwork,
    })
    if (mode === 'history') {
      setAppliedDate(date || undefined)
    }
    setPage(0)
  }

  return (
    <Box component="section">
      <Typography variant="h6">Consultar saldos</Typography>

      <RadioGroup
        row
        value={mode}
        onChange={(event) => setMode(event.target.value as Mode)}
        sx={{ mt: 1 }}
      >
        <FormControlLabel value="current" control={<Radio size="small" />} label="Saldo atual" />
        <FormControlLabel value="history" control={<Radio size="small" />} label="Saldo numa data" />
      </RadioGroup>

      <Box component="form" onSubmit={handleSubmit} sx={{ mt: 1 }}>
        <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'flex-end' }}>
          <TextField
            label="Carteira"
            size="small"
            value={walletAddress}
            onChange={(event) => setWalletAddress(event.target.value)}
            placeholder="0x..."
            slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
            sx={{ width: 240 }}
          />
          {manualContract ? (
            <Stack sx={{ gap: 0.25 }}>
              <TextField
                label="Contrato"
                size="small"
                value={contractAddress}
                onChange={(event) => setContractAddress(event.target.value)}
                placeholder="0x..."
                slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
                sx={{ width: 240 }}
              />
              <Link component="button" type="button" variant="caption" onClick={() => setManualContract(false)}>
                escolher da lista
              </Link>
            </Stack>
          ) : (
            <Stack sx={{ gap: 0.25 }}>
              <ContractAutocomplete
                value={contractRef}
                onChange={setContractRef}
                label="Contrato"
                sx={{ width: 260 }}
              />
              <Link component="button" type="button" variant="caption" onClick={() => setManualContract(true)}>
                digitar endereço manualmente
              </Link>
            </Stack>
          )}
          <TextField
            label="Token ID"
            size="small"
            value={tokenId}
            onChange={(event) => setTokenId(event.target.value)}
            sx={{ width: 140 }}
          />
          {manualContract && <NetworkSelect value={network} allowEmpty onChange={setNetwork} />}

          {mode === 'history' && (
            <TextField
              label="Data"
              size="small"
              type="date"
              required
              value={date}
              onChange={(event) => setDate(event.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
          )}

          <Button type="submit" variant="contained">
            Consultar
          </Button>
        </Stack>
      </Box>

      {isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Carregando...
        </Typography>
      )}
      {isError && <ApiErrorAlert error={error} />}

      {data && data.content.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Nenhum saldo encontrado com esses filtros.
        </Typography>
      )}

      {data && data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Carteira</TableCell>
                <TableCell>Contrato</TableCell>
                <TableCell>Token ID</TableCell>
                <TableCell>Saldo</TableCell>
                {mode === 'history' && <TableCell>Bloco</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((row, index) => (
                <TableRow key={`${row.walletAddress}-${row.contractAddress}-${row.tokenId ?? index}`}>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{row.walletAddress}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{row.contractAddress}</TableCell>
                  <TableCell>{row.tokenId ?? '—'}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{row.balance}</TableCell>
                  {mode === 'history' && isSnapshot(row) && <TableCell>{row.blockNumber}</TableCell>}
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
    </Box>
  )
}
