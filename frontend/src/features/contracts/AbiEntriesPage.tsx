import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import {
  Alert,
  Box,
  Button,
  Chip,
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
import type { ChipProps } from '@mui/material'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import { AbiEntryKind } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { AbiEntryKindSelect } from './AbiEntryKindSelect'
import { formatAbiEntryParams } from './abiEntryFormat'
import { useContractAbiEntries } from './useContracts'
import { UpgradeabilityBanner } from './UpgradeabilityBanner'

const KIND_COLORS: Record<AbiEntryKind, ChipProps['color']> = {
  [AbiEntryKind.Function]: 'primary',
  [AbiEntryKind.Event]: 'success',
  [AbiEntryKind.Error]: 'error',
  [AbiEntryKind.Constructor]: 'secondary',
  [AbiEntryKind.Fallback]: 'warning',
  [AbiEntryKind.Receive]: 'info',
}

/**
 * Explorador de ABI (E24) — tela nova, somente leitura, listando **todos** os itens do ABI cadastrado
 * de um contrato (funções de qualquer `stateMutability`, eventos, erros customizados/motivos de
 * revert declarados, constructor/fallback/receive) numa tabela só, com faixa de blocos por item
 * (`GET /v1/contracts/{address}/abi/entries`). Ponto de entrada: botão em cada linha da tabela de
 * versões de ABI (`AbiSection`) — não filtra pela versão clicada, o catálogo já mostra a faixa de
 * blocos de cada item e o usuário filtra visualmente por `Tipo` se quiser (decisão do usuário,
 * roadmap E24). O mesmo item pode aparecer mais de uma vez (proxy upgradável, DEC-007) — não é
 * de-duplicado, é informação útil sobre o que mudou entre versões.
 */
export function AbiEntriesPage() {
  const { address } = useParams<{ address: string }>()
  const navigate = useNavigate()
  const [kind, setKind] = useState<AbiEntryKind | ''>('')
  const entries = useContractAbiEntries(address)

  if (!address) {
    return (
      <Box sx={{ p: 3 }}>
        <ApiErrorAlert error={new Error('Endereço do contrato ausente na URL.')} />
      </Box>
    )
  }

  const allEntries = entries.data ?? []
  const filtered = kind === '' ? allEntries : allEntries.filter((entry) => entry.kind === kind)

  return (
    <Box sx={{ p: 3 }}>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate(`/contracts/${address}`)} sx={{ mb: 2 }}>
        Voltar
      </Button>

      <Typography variant="h5" sx={{ fontWeight: 600 }}>
        Métodos, eventos e erros do contrato
      </Typography>
      <Box sx={{ mt: 0.5 }}>
        <CopyableHash value={address} headLength={42} tailLength={0} />
      </Box>

      <UpgradeabilityBanner address={address} />

      <Box sx={{ mt: 3 }}>
        <AbiEntryKindSelect value={kind} onChange={setKind} />
      </Box>

      {entries.isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Carregando...
        </Typography>
      )}
      {entries.isError && <ApiErrorAlert error={entries.error} />}

      {entries.data && allEntries.length === 0 && (
        <Alert severity="info" sx={{ mt: 3 }}>
          Nenhuma versão de ABI cadastrada ainda para este contrato — sem ABI não há métodos, eventos
          ou erros para listar.
        </Alert>
      )}

      {allEntries.length > 0 && filtered.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Nenhum item do tipo selecionado.
        </Typography>
      )}

      {filtered.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Tipo</TableCell>
                <TableCell>Nome</TableCell>
                <TableCell>Mutabilidade</TableCell>
                <TableCell>Inputs</TableCell>
                <TableCell>Outputs</TableCell>
                <TableCell>Bloco de validade</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {filtered.map((entry, index) => (
                <TableRow key={`${entry.kind}-${entry.name ?? ''}-${entry.validFromBlock}-${index}`} hover>
                  <TableCell>
                    <Chip size="small" label={entry.kind} color={KIND_COLORS[entry.kind]} />
                  </TableCell>
                  <TableCell>{entry.name ?? '—'}</TableCell>
                  <TableCell>{entry.stateMutability ?? '—'}</TableCell>
                  <TableCell>{formatAbiEntryParams(entry.inputs)}</TableCell>
                  <TableCell>{formatAbiEntryParams(entry.outputs)}</TableCell>
                  <TableCell>
                    <Stack direction="row" sx={{ alignItems: 'center', gap: 0.5 }}>
                      <span>{entry.validFromBlock}</span>
                      <span>–</span>
                      {entry.validToBlock ?? <Chip size="small" label="vigente" color="success" />}
                    </Stack>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}
