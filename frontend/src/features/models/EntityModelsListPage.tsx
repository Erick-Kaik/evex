import { useState } from 'react'
import { useNavigate } from 'react-router'
import {
  Alert,
  Box,
  Button,
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
import type { Network } from '../../api/api'
import { EntityModelStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { Pagination } from '../../components/Pagination'
import { RowActions } from '../../components/RowActions'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import { useEntityModels, useUpdateEntityModelStatus } from './useEntityModels'

const PAGE_SIZE = 20

const STATUS_OPTIONS: StatusOption<EntityModelStatus>[] = [
  { value: EntityModelStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: EntityModelStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

/**
 * Listagem de Modelos de Entidade cadastrados (E19/DEC-082/083) — cópia estrutural de
 * `EnrichmentProfilesListPage` (padrão E18): filtro por rede, paginação, status como toggle rápido,
 * ações "Ver"/"Editar" por linha. Cadastro em página dedicada (`/entity-models/new`). Sem DELETE.
 */
export function EntityModelsListPage() {
  const navigate = useNavigate()
  const [network, setNetwork] = useState<Network | ''>('')
  const [page, setPage] = useState(0)

  const { data, isPending, isError, error } = useEntityModels(
    network === '' ? undefined : network,
    page,
    PAGE_SIZE,
  )

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>
          Modelos de Entidade
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/entity-models/new')}>
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
          Nenhum Modelo de Entidade cadastrado ainda. Use "Cadastrar" para adicionar o primeiro.
        </Alert>
      )}

      {data && data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Nome</TableCell>
                <TableCell>Rede</TableCell>
                <TableCell>Contribuidores</TableCell>
                <TableCell>Status</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((model) => (
                <EntityModelRow
                  key={model.id}
                  model={{
                    id: model.id,
                    name: model.name,
                    network: model.network,
                    contributorCount: model.contributors.length,
                    status: model.status,
                  }}
                />
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

interface EntityModelRowProps {
  model: {
    id: string
    name: string
    network: Network
    contributorCount: number
    status: EntityModelStatus
  }
}

function EntityModelRow({ model }: EntityModelRowProps) {
  const navigate = useNavigate()
  const updateStatus = useUpdateEntityModelStatus(model.id)

  return (
    <TableRow hover>
      <TableCell>{model.name}</TableCell>
      <TableCell>{model.network}</TableCell>
      <TableCell>{model.contributorCount}</TableCell>
      <TableCell>
        <StatusToggle
          value={model.status}
          options={STATUS_OPTIONS}
          disabled={updateStatus.isPending}
          onChange={(status) => updateStatus.mutate({ status })}
        />
      </TableCell>
      <TableCell align="right">
        <RowActions
          onView={() => navigate(`/entity-models/${model.id}`)}
          onEdit={() => navigate(`/entity-models/${model.id}/edit`)}
        />
      </TableCell>
    </TableRow>
  )
}
