import { useState } from 'react'
import { useNavigate } from 'react-router'
import {
  Alert,
  Box,
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
import AddIcon from '@mui/icons-material/Add'
import Button from '@mui/material/Button'
import type { Network } from '../../api/api'
import { ContractStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { NetworkSelect } from '../../components/NetworkSelect'
import { Pagination } from '../../components/Pagination'
import { useContracts } from './useContracts'
import { ContractFormDialog } from './ContractFormDialog'

const PAGE_SIZE = 20

const STATUS_COLOR: Record<ContractStatus, 'success' | 'default' | 'warning'> = {
  [ContractStatus.Active]: 'success',
  [ContractStatus.Paused]: 'warning',
  [ContractStatus.Archived]: 'default',
}

/** Listagem de contratos monitorados — filtro por rede, paginação, cadastro via modal (E13/DEC-060). */
export function ContractsListPage() {
  const navigate = useNavigate()
  const [network, setNetwork] = useState<Network | ''>('')
  const [page, setPage] = useState(0)
  const [formOpen, setFormOpen] = useState(false)

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
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setFormOpen(true)}>
          Novo contrato
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
          Nenhum contrato cadastrado ainda. Use "Novo contrato" para monitorar o primeiro.
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
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((contract) => (
                <TableRow
                  key={contract.id}
                  hover
                  onClick={() => navigate(`/contracts/${contract.address}`)}
                  sx={{ cursor: 'pointer' }}
                >
                  <TableCell onClick={(event) => event.stopPropagation()}>
                    <CopyableHash value={contract.address} />
                  </TableCell>
                  <TableCell>{contract.name}</TableCell>
                  <TableCell>{contract.network}</TableCell>
                  <TableCell>
                    <Chip size="small" label={contract.status} color={STATUS_COLOR[contract.status]} />
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

      <ContractFormDialog
        open={formOpen}
        onClose={() => setFormOpen(false)}
        onCreated={(address) => {
          setFormOpen(false)
          navigate(`/contracts/${address}`)
        }}
      />
    </Box>
  )
}
