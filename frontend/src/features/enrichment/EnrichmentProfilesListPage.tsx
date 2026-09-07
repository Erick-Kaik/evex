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
import { EnrichmentProfileStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { Pagination } from '../../components/Pagination'
import { RowActions } from '../../components/RowActions'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import { useEnrichmentProfiles, useUpdateEnrichmentProfileStatus } from './useEnrichmentProfiles'

const PAGE_SIZE = 20

const STATUS_OPTIONS: StatusOption<EnrichmentProfileStatus>[] = [
  { value: EnrichmentProfileStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: EnrichmentProfileStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

/**
 * Listagem de Perfis de Enriquecimento cadastrados (E19/DEC-082/083) — cópia estrutural de
 * `WalletsListPage`/`ContractsListPage` (padrão E18): filtro por rede, paginação, status como toggle
 * rápido (ACTIVE/ARCHIVED, sem PAUSED — arquivar não bloqueia resolução, só some das novas opções de
 * referência em `WebhookForm`), ações "Ver"/"Editar" por linha. Cadastro em página dedicada
 * (`/enrichment-profiles/new`). Sem DELETE — mesma regra do resto da API.
 */
export function EnrichmentProfilesListPage() {
  const navigate = useNavigate()
  const [network, setNetwork] = useState<Network | ''>('')
  const [page, setPage] = useState(0)

  const { data, isPending, isError, error } = useEnrichmentProfiles(
    network === '' ? undefined : network,
    page,
    PAGE_SIZE,
  )

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>
          Perfis de Enriquecimento
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/enrichment-profiles/new')}>
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
          Nenhum Perfil de Enriquecimento cadastrado ainda. Use "Cadastrar" para adicionar o primeiro.
        </Alert>
      )}

      {data && data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Nome</TableCell>
                <TableCell>Rede</TableCell>
                <TableCell>Chamadas</TableCell>
                <TableCell>Status</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((profile) => (
                <EnrichmentProfileRow
                  key={profile.id}
                  profile={{
                    id: profile.id,
                    name: profile.name,
                    network: profile.network,
                    callCount: profile.calls.length,
                    status: profile.status,
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

interface EnrichmentProfileRowProps {
  profile: {
    id: string
    name: string
    network: Network
    callCount: number
    status: EnrichmentProfileStatus
  }
}

function EnrichmentProfileRow({ profile }: EnrichmentProfileRowProps) {
  const navigate = useNavigate()
  const updateStatus = useUpdateEnrichmentProfileStatus(profile.id)

  return (
    <TableRow hover>
      <TableCell>{profile.name}</TableCell>
      <TableCell>{profile.network}</TableCell>
      <TableCell>{profile.callCount}</TableCell>
      <TableCell>
        <StatusToggle
          value={profile.status}
          options={STATUS_OPTIONS}
          disabled={updateStatus.isPending}
          onChange={(status) => updateStatus.mutate({ status })}
        />
      </TableCell>
      <TableCell align="right">
        <RowActions
          onView={() => navigate(`/enrichment-profiles/${profile.id}`)}
          onEdit={() => navigate(`/enrichment-profiles/${profile.id}/edit`)}
        />
      </TableCell>
    </TableRow>
  )
}
