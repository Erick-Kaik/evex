import { useState } from 'react'
import { useNavigate } from 'react-router'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
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
import { WalletStatus } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { CopyableHash } from '../../components/CopyableHash'
import { NetworkSelect } from '../../components/NetworkSelect'
import { Pagination } from '../../components/Pagination'
import { RowActions } from '../../components/RowActions'
import { StatusToggle } from '../../components/StatusToggle'
import type { StatusOption } from '../../components/StatusToggle'
import { useUpdateWalletStatus, useWalletSuggestions, useWallets } from './useWallets'

const PAGE_SIZE = 20

const STATUS_OPTIONS: StatusOption<WalletStatus>[] = [
  { value: WalletStatus.Active, label: 'ACTIVE', color: 'success' },
  { value: WalletStatus.Paused, label: 'PAUSED', color: 'warning' },
  { value: WalletStatus.Archived, label: 'ARCHIVED', color: 'default' },
]

/**
 * Listagem de carteiras cadastradas — cópia estrutural de `ContractsListPage` (E16/E18) pro domínio
 * de Carteiras (E22): filtro por rede, paginação, status como toggle rápido, ações "Ver"/"Editar" por
 * linha. Cadastro em página dedicada (`/wallets/new`). Ganha, a mais que `ContractsListPage`, o card
 * "Carteiras sugeridas" (`GET /v1/wallets/suggestions`).
 */
export function WalletsListPage() {
  const navigate = useNavigate()
  const [network, setNetwork] = useState<Network | ''>('')
  const [page, setPage] = useState(0)

  const { data, isPending, isError, error } = useWallets(
    network === '' ? undefined : network,
    page,
    PAGE_SIZE,
  )

  return (
    <Box sx={{ p: 3 }}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h5" sx={{ fontWeight: 600 }}>
          Carteiras cadastradas
        </Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/wallets/new')}>
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

      <WalletSuggestionsCard network={network === '' ? undefined : network} />

      {isPending && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
          Carregando...
        </Typography>
      )}
      {isError && <ApiErrorAlert error={error} />}

      {data && data.content.length === 0 && (
        <Alert severity="info" sx={{ mt: 3 }}>
          Nenhuma carteira cadastrada ainda. Use "Cadastrar" para adicionar a primeira.
        </Alert>
      )}

      {data && data.content.length > 0 && (
        <TableContainer component={Paper} variant="outlined" sx={{ mt: 3 }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Endereço</TableCell>
                <TableCell>Label</TableCell>
                <TableCell>Rede</TableCell>
                <TableCell>Status</TableCell>
                <TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {data.content.map((wallet) => (
                <WalletRow key={wallet.id} wallet={wallet} />
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

interface WalletRowProps {
  wallet: {
    id: string
    address: string
    label: string
    network: Network
    status: WalletStatus
  }
}

function WalletRow({ wallet }: WalletRowProps) {
  const navigate = useNavigate()
  const updateStatus = useUpdateWalletStatus(wallet.address)

  return (
    <TableRow hover>
      <TableCell>
        <CopyableHash value={wallet.address} />
      </TableCell>
      <TableCell>{wallet.label}</TableCell>
      <TableCell>{wallet.network}</TableCell>
      <TableCell>
        <StatusToggle
          value={wallet.status}
          options={STATUS_OPTIONS}
          disabled={updateStatus.isPending}
          onChange={(status) => updateStatus.mutate({ status })}
        />
      </TableCell>
      <TableCell align="right">
        <RowActions
          onView={() => navigate(`/wallets/${wallet.address}`)}
          onEdit={() => navigate(`/wallets/${wallet.address}/edit`)}
        />
      </TableCell>
    </TableRow>
  )
}

interface WalletSuggestionsCardProps {
  network?: Network
}

/**
 * Carteiras sugeridas (`GET /v1/wallets/suggestions`, E22) — endereços já observados em
 * `asset_balances` que ainda não estão cadastrados. Mesmo espírito do botão "Ver sugestões
 * automáticas" de ABI (E6), mas inline na listagem em vez de dialog — a lista tende a ser curta por
 * natureza (mesma decisão de não paginar do backend). Cada linha tem um atalho "Cadastrar" que leva
 * pro form de criação com o endereço pré-preenchido via query string (`/wallets/new?address=0x...`,
 * decisão de UX registrada em `docs/decisions.md`).
 */
function WalletSuggestionsCard({ network }: WalletSuggestionsCardProps) {
  const navigate = useNavigate()
  const suggestions = useWalletSuggestions(network)

  if (suggestions.isPending || suggestions.isError) {
    return null
  }

  if ((suggestions.data ?? []).length === 0) {
    return null
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="subtitle2" gutterBottom>
          Carteiras sugeridas
        </Typography>
        <Typography variant="caption" color="text.secondary">
          Endereços já observados em saldos projetados, ainda não cadastrados.
        </Typography>
        <Stack sx={{ mt: 1, gap: 1 }}>
          {(suggestions.data ?? []).map((address) => (
            <Stack
              key={address}
              direction="row"
              sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2 }}
            >
              <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                {address}
              </Typography>
              <Button size="small" onClick={() => navigate(`/wallets/new?address=${address}`)}>
                Cadastrar
              </Button>
            </Stack>
          ))}
        </Stack>
      </CardContent>
    </Card>
  )
}
