import { Fragment, useState } from 'react'
import type { FormEvent } from 'react'
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
import { AddressCell } from '../../components/AddressCell'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { Pagination } from '../../components/Pagination'
import { ContractAutocomplete } from '../../components/ContractAutocomplete'
import type { ContractRef } from '../../components/ContractAutocomplete'
import { WalletAutocomplete } from '../../components/WalletAutocomplete'
import type { WalletRef } from '../../components/WalletAutocomplete'
import { EventPicker } from '../../components/EventPicker'
import { DecodedParamValue } from './DecodedParamValue'
import type { EventFilters } from './useEvents'
import { useEvents } from './useEvents'

const PAGE_SIZE = 20

/**
 * Visualização de eventos indexados com filtros básicos (doc §6) — raw e decodificados, parâmetros
 * decodificados formatados por tipo em runtime (E13, ver `DecodedParamValue`).
 *
 * E16: filtro de contrato via `<ContractAutocomplete>` + evento via `<EventPicker>` quando há
 * contrato selecionado (degradação natural pra texto livre quando não); coluna "Contrato" na tabela
 * (`<AddressCell>`, E22); `raw` → "sem ABI" com tooltip. Como o Evex indexa a rede inteira (DEC-001) e
 * a tabela mostra contratos não-monitorados, um link discreto revela um campo de endereço livre
 * (`0x…`) para filtrar eventos de um contrato fora da lista de monitorados.
 *
 * E22: filtro adicional "Carteira" via `<WalletAutocomplete>` (mesmo padrão dual autocomplete/manual
 * do contrato) — `walletAddress` casa contra qualquer parâmetro decodificado do tipo `address` do
 * evento (`jsonb_path_exists` no backend, não uma coluna fixa).
 */
export function EventsPage() {
  const [contractRef, setContractRef] = useState<ContractRef | null>(null)
  const [manualContract, setManualContract] = useState(false)
  const [manualAddress, setManualAddress] = useState('')
  const [walletRef, setWalletRef] = useState<WalletRef | null>(null)
  const [manualWallet, setManualWallet] = useState(false)
  const [manualWalletAddress, setManualWalletAddress] = useState('')
  const [eventName, setEventName] = useState('')
  const [fromBlock, setFromBlock] = useState('')
  const [toBlock, setToBlock] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  const [filters, setFilters] = useState<EventFilters>({})
  const [page, setPage] = useState(0)
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const { data, isPending, isError, error } = useEvents(filters, page, PAGE_SIZE)

  const contractAddress = manualContract ? manualAddress.trim() : (contractRef?.address ?? '')
  const walletAddress = manualWallet ? manualWalletAddress.trim() : (walletRef?.address ?? '')
  // Só usa o `<EventPicker>` (catálogo) quando o contrato veio da lista de monitorados.
  const showEventPicker = !manualContract && contractAddress !== ''

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFilters({
      contractAddress: contractAddress || undefined,
      walletAddress: walletAddress || undefined,
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
            <TextField
              label="Contrato"
              size="small"
              value={manualAddress}
              onChange={(event) => setManualAddress(event.target.value)}
              placeholder="0x..."
              slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
              sx={{ width: 260 }}
            />
          ) : (
            <ContractAutocomplete
              value={contractRef}
              onChange={(next) => {
                setContractRef(next)
                setEventName('')
              }}
              sx={{ width: 260 }}
            />
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
          {manualWallet ? (
            <TextField
              label="Carteira"
              size="small"
              value={manualWalletAddress}
              onChange={(event) => setManualWalletAddress(event.target.value)}
              placeholder="0x..."
              slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
              sx={{ width: 260 }}
            />
          ) : (
            <WalletAutocomplete value={walletRef} onChange={setWalletRef} sx={{ width: 260 }} />
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

        {/* Fora da `Stack` de filtros de propósito: um item mais alto (label + link) dentro de uma
            `Stack` `alignItems: 'flex-end'` empurra os campos vizinhos mais curtos pra baixo,
            deixando o campo de contrato visualmente "acima" dos outros. */}
        <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2 }}>
          {manualContract ? (
            <Link
              component="button"
              type="button"
              variant="caption"
              sx={{ display: 'block', mt: 0.5 }}
              onClick={() => {
                setManualContract(false)
                setManualAddress('')
              }}
            >
              escolher contrato da lista
            </Link>
          ) : (
            <Link
              component="button"
              type="button"
              variant="caption"
              sx={{ display: 'block', mt: 0.5 }}
              onClick={() => {
                setManualContract(true)
                setContractRef(null)
                setEventName('')
              }}
            >
              digitar endereço do contrato manualmente
            </Link>
          )}
          {manualWallet ? (
            <Link
              component="button"
              type="button"
              variant="caption"
              sx={{ display: 'block', mt: 0.5 }}
              onClick={() => {
                setManualWallet(false)
                setManualWalletAddress('')
              }}
            >
              escolher carteira da lista
            </Link>
          ) : (
            <Link
              component="button"
              type="button"
              variant="caption"
              sx={{ display: 'block', mt: 0.5 }}
              onClick={() => {
                setManualWallet(true)
                setWalletRef(null)
              }}
            >
              digitar endereço da carteira manualmente
            </Link>
          )}
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
                return (
                  <Fragment key={evt.id}>
                    <TableRow hover>
                      <TableCell>{evt.blockNumber}</TableCell>
                      <TableCell>
                        <AddressCell address={evt.contractAddress} kind="contract" />
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
