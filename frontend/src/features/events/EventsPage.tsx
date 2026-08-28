import { Fragment, useState } from 'react'
import type { FormEvent } from 'react'
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
  TextField,
  Typography,
} from '@mui/material'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { Pagination } from '../../components/Pagination'
import { DecodedParamValue } from './DecodedParamValue'
import type { EventFilters } from './useEvents'
import { useEvents } from './useEvents'

const PAGE_SIZE = 20

/**
 * Visualização de eventos indexados com filtros básicos (doc §6) — raw e decodificados, parâmetros
 * decodificados formatados por tipo em runtime (E13, ver `DecodedParamValue`) em vez do
 * `JSON.stringify` cru anterior.
 */
export function EventsPage() {
  const [contractAddress, setContractAddress] = useState('')
  const [eventName, setEventName] = useState('')
  const [fromBlock, setFromBlock] = useState('')
  const [toBlock, setToBlock] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  const [filters, setFilters] = useState<EventFilters>({})
  const [page, setPage] = useState(0)
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const { data, isPending, isError, error } = useEvents(filters, page, PAGE_SIZE)

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
          <TextField
            label="Contrato"
            size="small"
            value={contractAddress}
            onChange={(event) => setContractAddress(event.target.value)}
            placeholder="0x..."
            slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
            sx={{ width: 240 }}
          />
          <TextField
            label="Nome do evento"
            size="small"
            value={eventName}
            onChange={(event) => setEventName(event.target.value)}
            placeholder="ex.: Transfer"
            sx={{ width: 160 }}
          />
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
                <TableCell>Tx hash</TableCell>
                <TableCell>Evento</TableCell>
                <TableCell>Timestamp</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((evt) => (
                <Fragment key={evt.id}>
                  <TableRow hover>
                    <TableCell>{evt.blockNumber}</TableCell>
                    <TableCell>
                      <CopyableHash value={evt.txHash} />
                    </TableCell>
                    <TableCell>
                      {evt.decoded ? evt.eventName : <Chip size="small" label="raw" color="warning" variant="outlined" />}
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
                      <TableCell colSpan={5} sx={{ p: 0, borderBottom: expandedId === evt.id ? undefined : 'none' }}>
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
