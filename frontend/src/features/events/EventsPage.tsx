import { Fragment, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { Link as RouterLink } from 'react-router'
import {
  Box,
  Button,
  Chip,
  Collapse,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { Pagination } from '../../components/Pagination'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { EventPicker } from '../../components/EventPicker'
import { useMonitoredContractsList } from '../../components/contractCatalog'
import { DecodedParamValue } from './DecodedParamValue'
import type { EventFilters } from './useEvents'
import { useEvents } from './useEvents'

const PAGE_SIZE = 20

function shortenAddress(address: string): string {
  return address.length > 12 ? `${address.slice(0, 8)}…${address.slice(-4)}` : address
}

/**
 * Visualização de eventos indexados com filtros básicos (doc §6) — raw e decodificados, parâmetros
 * decodificados formatados por tipo em runtime (E13, ver `DecodedParamValue`).
 *
 * E16: filtro de contrato via `<ContractAutocomplete>` + evento via `<EventPicker>` quando há
 * contrato selecionado (degradação natural pra texto livre quando não); coluna "Contrato" na tabela;
 * `raw` → "sem ABI" com tooltip. Como o Evex indexa a rede inteira (DEC-001) e a tabela mostra
 * contratos não-monitorados, um link discreto revela um campo de endereço livre (`0x…`) para
 * filtrar eventos de um contrato fora da lista de monitorados.
 */
export function EventsPage() {
  const [contractRef, setContractRef] = useState<ContractRef | null>(null)
  const [manualContract, setManualContract] = useState(false)
  const [manualAddress, setManualAddress] = useState('')
  const [eventName, setEventName] = useState('')
  const [fromBlock, setFromBlock] = useState('')
  const [toBlock, setToBlock] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  const [filters, setFilters] = useState<EventFilters>({})
  const [page, setPage] = useState(0)
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const { data, isPending, isError, error } = useEvents(filters, page, PAGE_SIZE)

  // Nomes de contrato pra coluna "Contrato" — uma consulta só, compartilhada (React Query).
  const contractsList = useMonitoredContractsList()
  const contractsByAddress = useMemo(() => {
    const map = new Map<string, string>()
    for (const contract of contractsList.data ?? []) {
      map.set(contract.address.toLowerCase(), contract.name)
    }
    return map
  }, [contractsList.data])

  const contractAddress = manualContract ? manualAddress.trim() : (contractRef?.address ?? '')
  // Só usa o `<EventPicker>` (catálogo) quando o contrato veio da lista de monitorados.
  const showEventPicker = !manualContract && contractAddress !== ''

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFilters({
      contractAddress: contractAddress || undefined,
      eventName: eventName || undefined,
      fromBlock: fromBlock === '' ? undefined : Number(fromBlock),
      toBlock: toBlock === '' ? undefined : Number(toBlock),
      fromDate: fromDate ? new Date(fromDate).toISOString() : undefined,
      toDate: toDate ? new Date(toDate).toISOString() : undefined,
    })
    setPage(0)
  }

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600 }}>
        Eventos indexados
      </Typography>

      <Box component="form" onSubmit={handleSubmit} sx={{ mt: 3 }}>
        <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'flex-end' }}>
          {manualContract ? (
            <Stack sx={{ gap: 0.25 }}>
              <TextField
                label="Contrato"
                size="small"
                value={manualAddress}
                onChange={(event) => setManualAddress(event.target.value)}
                placeholder="0x..."
                slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
                sx={{ width: 260 }}
              />
              <Link
                component="button"
                type="button"
                variant="caption"
                onClick={() => {
                  setManualContract(false)
                  setManualAddress('')
                }}
              >
                escolher da lista
              </Link>
            </Stack>
          ) : (
            <Stack sx={{ gap: 0.25 }}>
              <ContractAutocomplete
                value={contractRef}
                onChange={(next) => {
                  setContractRef(next)
                  setEventName('')
                }}
                sx={{ width: 260 }}
              />
              <Link
                component="button"
                type="button"
                variant="caption"
                onClick={() => {
                  setManualContract(true)
                  setContractRef(null)
                  setEventName('')
                }}
              >
                digitar endereço manualmente
              </Link>
            </Stack>
          )}
          {showEventPicker ? (
            <EventPicker
              address={contractAddress}
              value={eventName}
              onChange={(name) => setEventName(name)}
              sx={{ width: 200 }}
            />
          ) : (
            <TextField
              label="Nome do evento"
              size="small"
              value={eventName}
              onChange={(event) => setEventName(event.target.value)}
              placeholder="ex.: Transfer"
              sx={{ width: 160 }}
            />
          )}
          <TextField
            label="Bloco inicial"
            size="small"
            type="number"
            slotProps={{ htmlInput: { min: 0 } }}
            value={fromBlock}
            onChange={(event) => setFromBlock(event.target.value)}
            sx={{ width: 140 }}
          />
          <TextField
            label="Bloco final"
            size="small"
            type="number"
            slotProps={{ htmlInput: { min: 0 } }}
            value={toBlock}
            onChange={(event) => setToBlock(event.target.value)}
            sx={{ width: 140 }}
          />
          <TextField
            label="De"
            size="small"
            type="datetime-local"
            value={fromDate}
            onChange={(event) => setFromDate(event.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="Até"
            size="small"
            type="datetime-local"
            value={toDate}
            onChange={(event) => setToDate(event.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <Button type="submit" variant="contained">
            Filtrar
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
          Nenhum evento encontrado com esses filtros.
        </Typography>
      )}

      {data && data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Bloco</TableCell>
                <TableCell>Contrato</TableCell>
                <TableCell>Tx hash</TableCell>
                <TableCell>Evento</TableCell>
                <TableCell>Timestamp</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((evt) => {
                const contractName = contractsByAddress.get(evt.contractAddress.toLowerCase())
                return (
                  <Fragment key={evt.id}>
                    <TableRow hover>
                      <TableCell>{evt.blockNumber}</TableCell>
                      <TableCell>
                        {contractName ? (
                          <Link component={RouterLink} to={`/contracts/${evt.contractAddress}`}>
                            {contractName}
                          </Link>
                        ) : (
                          <Tooltip title={evt.contractAddress}>
                            <Box component="span" sx={{ fontFamily: 'monospace' }}>
                              {shortenAddress(evt.contractAddress)}
                            </Box>
                          </Tooltip>
                        )}
                      </TableCell>
                      <TableCell>
                        <CopyableHash value={evt.txHash} />
                      </TableCell>
                      <TableCell>
                        {evt.decoded ? (
                          evt.eventName
                        ) : (
                          <Tooltip title="Evento indexado mas ainda não decodificado — cadastre a ABI do contrato">
                            <Chip size="small" label="sem ABI" color="warning" variant="outlined" />
                          </Tooltip>
                        )}
                      </TableCell>
                      <TableCell>
                        {evt.blockTimestamp ? new Date(evt.blockTimestamp).toLocaleString('pt-BR') : '—'}
                      </TableCell>
                      <TableCell align="right">
                        {evt.decoded && (
                          <Button size="small" onClick={() => setExpandedId(expandedId === evt.id ? null : evt.id)}>
                            {expandedId === evt.id ? 'ocultar' : 'ver parâmetros'}
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                    {evt.decoded && (
                      <TableRow>
                        <TableCell colSpan={6} sx={{ p: 0, borderBottom: expandedId === evt.id ? undefined : 'none' }}>
                          <Collapse in={expandedId === evt.id} unmountOnExit>
                            <Box sx={{ p: 2, bgcolor: 'action.hover' }}>
                              {evt.decodedParameters && Object.keys(evt.decodedParameters).length > 0 ? (
                                <Table size="small">
                                  <TableHead>
                                    <TableRow>
                                      <TableCell>Parâmetro</TableCell>
                                      <TableCell>Valor</TableCell>
                                    </TableRow>
                                  </TableHead>
                                  <TableBody>
                                    {Object.entries(evt.decodedParameters).map(([key, paramValue]) => (
                                      <TableRow key={key}>
                                        <TableCell sx={{ fontFamily: 'monospace' }}>{key}</TableCell>
                                        <TableCell>
                                          <DecodedParamValue value={paramValue} />
                                        </TableCell>
                                      </TableRow>
                                    ))}
                                  </TableBody>
                                </Table>
                              ) : (
                                <Typography variant="body2" color="text.secondary">
                                  Sem parâmetros.
                                </Typography>
                              )}
                            </Box>
                          </Collapse>
                        </TableCell>
                      </TableRow>
                    )}
                  </Fragment>
                )
              })}
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
