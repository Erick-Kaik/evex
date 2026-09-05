import { useState } from 'react'
import { useNavigate } from 'react-router'
import {
  Alert,
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
import type { Network } from '../../api/api'
import { ContractStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { NetworkSelect } from '../../components/NetworkSelect'
import { Pagination } from '../../components/Pagination'
import { RowActions } from '../../components/RowActions'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import { useContracts, useUpdateContractStatus } from './useContracts'

const PAGE_SIZE = 20

const STATUS_OPTIONS: StatusOption<ContractStatus>[] = [
  { value: ContractStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: ContractStatus.Paused, label: 'PAUSED', color: 'warning' },
  { value: ContractStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

/**
 * Listagem de contratos monitorados — filtro por rede, paginação, status como toggle rápido e ações
 * "Ver"/"Editar" por linha (padrão E18). Cadastro em página dedicada (`/contracts/new`), não mais em
 * modal (supera E13/DEC-060 nesse ponto — ver DEC-079).
 */
export function ContractsListPage() {
  const navigate = useNavigate()
  const [network, setNetwork] = useState<Network | ''>('')
  const [page, setPage] = useState(0)

  const { data, isPending, isError, error } = useContracts(
    network === '' ? undefined : network,
    page,
    PAGE_SIZE,
  )

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>
          Contratos monitorados
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/contracts/new')}>
          Cadastrar
        </Button>
      </Stack>

      <Box sx={{ mt: 3 }}>
        <NetworkSelect
          value={network}
          allowEmpty
          onChange={(value) => {
            setNetwork(value)
            setPage(0)
          }}
        />
      </Box>

      {isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Carregando...
        </Typography>
      )}
      {isError && <ApiErrorAlert error={error} />}

      {data && data.content.length === 0 && (
        <Alert severity="info" sx={{ mt: 3 }}>
          Nenhum contrato cadastrado ainda. Use "Cadastrar" para monitorar o primeiro.
        </Alert>
      )}

      {data && data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Endereço</TableCell>
                <TableCell>Nome</TableCell>
                <TableCell>Rede</TableCell>
                <TableCell>Status</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((contract) => (
                <ContractRow key={contract.id} contract={contract} />
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

interface ContractRowProps {
  contract: {
    id: string
    address: string
    name: string
    network: Network
    status: ContractStatus
  }
}

function ContractRow({ contract }: ContractRowProps) {
  const navigate = useNavigate()
  const updateStatus = useUpdateContractStatus(contract.address)

  return (
    <TableRow hover>
      <TableCell>
        <CopyableHash value={contract.address} />
      </TableCell>
      <TableCell>{contract.name}</TableCell>
      <TableCell>{contract.network}</TableCell>
      <TableCell>
        <StatusToggle
          value={contract.status}
          options={STATUS_OPTIONS}
          disabled={updateStatus.isPending}
          onChange={(status) => updateStatus.mutate({ status })}
        />
      </TableCell>
      <TableCell align="right">
        <RowActions
          onView={() => navigate(`/contracts/${contract.address}`)}
          onEdit={() => navigate(`/contracts/${contract.address}/edit`)}
        />
      </TableCell>
    </TableRow>
  )
}
