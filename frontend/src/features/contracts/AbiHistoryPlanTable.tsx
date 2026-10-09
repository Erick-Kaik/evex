import { Fragment, useState } from 'react'
import {
  Box,
  Chip,
  Collapse,
  IconButton,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown'
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp'
import type { AbiHistoryDiff, AbiHistoryItem } from '../../api/api'
import { CopyableHash } from '../../components/CopyableHash'

type ChipColor = 'success' | 'default' | 'error' | 'warning'

const STATUS_CHIP: Record<string, { label: string; color: ChipColor }> = {
  APPLY: { label: 'APPLY', color: 'success' },
  SKIP_ALREADY_IMPORTED: { label: 'SKIP_ALREADY_IMPORTED', color: 'default' },
  SKIP_IDENTICAL: { label: 'SKIP_IDENTICAL', color: 'default' },
  CONFLICT_MANUAL: { label: 'CONFLICT_MANUAL', color: 'error' },
  PENDING_NO_VERIFIED_ABI: { label: 'PENDING_NO_VERIFIED_ABI', color: 'warning' },
  BLOCKED_AFTER_PENDING: { label: 'BLOCKED_AFTER_PENDING', color: 'warning' },
}

/** Status extensível: valor desconhecido vira "não aplicável" (contrato da DEC-105). */
function statusChip(status: string): { label: string; color: ChipColor } {
  return STATUS_CHIP[status] ?? { label: 'não aplicável', color: 'default' }
}

interface AbiHistoryPlanTableProps {
  items: AbiHistoryItem[]
}

/** Tabela do plano de importação de histórico (DEC-105), com o diff da versão anterior expansível. */
export function AbiHistoryPlanTable({ items }: AbiHistoryPlanTableProps) {
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set())

  function toggle(sequence: number) {
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(sequence)) {
        next.delete(sequence)
      } else {
        next.add(sequence)
      }
      return next
    })
  }

  return (
    <Box sx={{ overflowX: 'auto' }}>
      <Table size="small" aria-label="Plano de importação do histórico de ABI">
        <TableHead>
          <TableRow>
            <TableCell />
            <TableCell>#</TableCell>
            <TableCell>Faixa de blocos</TableCell>
            <TableCell>Bloco do upgrade</TableCell>
            <TableCell>Implementação</TableCell>
            <TableCell>Transação</TableCell>
            <TableCell>Origem</TableCell>
            <TableCell>ABI disponível</TableCell>
            <TableCell>Status</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {items.map((item) => {
            const chip = statusChip(item.status)
            const isOpen = expanded.has(item.sequence)
            return (
              <Fragment key={item.sequence}>
                <TableRow>
                  <TableCell padding="checkbox">
                    <IconButton
                      size="small"
                      aria-label={`${isOpen ? 'ocultar' : 'ver'} diferenças do item ${item.sequence}`}
                      aria-expanded={isOpen}
                      onClick={() => toggle(item.sequence)}
                    >
                      {isOpen ? <KeyboardArrowUpIcon fontSize="small" /> : <KeyboardArrowDownIcon fontSize="small" />}
                    </IconButton>
                  </TableCell>
                  <TableCell>{item.sequence}</TableCell>
                  <TableCell>
                    {item.validFromBlock} → {item.validToBlock ?? 'aberto'}
                  </TableCell>
                  <TableCell>{item.upgradeBlockNumber ?? '—'}</TableCell>
                  <TableCell>
                    <CopyableHash value={item.implementationAddress} />
                  </TableCell>
                  <TableCell>{item.txHash ? <CopyableHash value={item.txHash} /> : '—'}</TableCell>
                  <TableCell>{item.upgradeSource}</TableCell>
                  <TableCell>{item.abiAvailable ? 'sim' : 'não'}</TableCell>
                  <TableCell>
                    <Chip size="small" label={chip.label} color={chip.color} />
                    {item.identicalToPrevious && (
                      <Chip size="small" variant="outlined" label="idêntica à anterior" sx={{ ml: 0.5 }} />
                    )}
                  </TableCell>
                </TableRow>
                <TableRow>
                  <TableCell colSpan={9} sx={{ py: 0, borderBottom: isOpen ? undefined : 'none' }}>
                    <Collapse in={isOpen} timeout="auto" unmountOnExit>
                      <ItemDetails item={item} />
                    </Collapse>
                  </TableCell>
                </TableRow>
              </Fragment>
            )
          })}
        </TableBody>
      </Table>
    </Box>
  )
}

function ItemDetails({ item }: { item: AbiHistoryItem }) {
  return (
    <Box sx={{ py: 1 }}>
      {item.statusReason && (
        <Typography variant="body2" sx={{ mb: 1 }}>
          {item.statusReason}
        </Typography>
      )}
      {item.diffFromPrevious ? (
        <DiffView diff={item.diffFromPrevious} />
      ) : (
        <Typography variant="body2" color="text.secondary">
          Sem diferenças para exibir (primeiro item ou ABI indisponível).
        </Typography>
      )}
    </Box>
  )
}

function DiffView({ diff }: { diff: AbiHistoryDiff }) {
  const groups: { label: string; values: string[]; color: 'success' | 'error' }[] = [
    { label: 'Eventos adicionados', values: diff.eventsAdded, color: 'success' },
    { label: 'Eventos removidos', values: diff.eventsRemoved, color: 'error' },
    { label: 'Métodos adicionados', values: diff.methodsAdded, color: 'success' },
    { label: 'Métodos removidos', values: diff.methodsRemoved, color: 'error' },
  ]

  if (groups.every((group) => group.values.length === 0)) {
    return (
      <Typography variant="body2" color="text.secondary">
        Nenhuma diferença de eventos/métodos em relação à versão anterior.
      </Typography>
    )
  }

  return (
    <Box>
      {groups
        .filter((group) => group.values.length > 0)
        .map((group) => (
          <Box key={group.label} sx={{ mb: 0.5 }}>
            <Typography variant="caption" color="text.secondary">
              {group.label} ({group.values.length})
            </Typography>
            <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
              {group.values.map((value) => (
                <Chip key={value} size="small" variant="outlined" color={group.color} label={value} />
              ))}
            </Box>
          </Box>
        ))}
    </Box>
  )
}
