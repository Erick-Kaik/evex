import { useState } from 'react'
import {
  Alert,
  Box,
  Card,
  CardContent,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { Pagination } from '../../components/Pagination'
import { AddressCell } from '../../components/AddressCell'
import { useBalances } from '../balances/useBalances'

const PAGE_SIZE = 10

interface WalletBalancesSectionProps {
  address: string
}

/**
 * Saldos desta carteira, na tela de detalhe (`WalletFormPage`) — espelho exato de
 * `ContractBalancesSection` (E21), mesma consulta de `BalanceQuerySection` (`GET /v1/balances`), só
 * que fixando `walletAddress` e sem os demais filtros (o usuário já está "dentro" da carteira).
 * Agrega saldos em qualquer contrato monitorado, complementando o inverso já existente
 * (`ContractBalancesSection`, "todos os saldos deste contrato").
 */
export function WalletBalancesSection({ address }: WalletBalancesSectionProps) {
  const [page, setPage] = useState(0)
  const { data, isPending, isError, error } = useBalances({ walletAddress: address }, page, PAGE_SIZE)

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="h6">Saldos desta carteira</Typography>

        {isPending && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
            Carregando...
          </Typography>
        )}
        {isError && <ApiErrorAlert error={error} />}

        {data && data.content.length === 0 && (
          <Alert severity="info" sx={{ mt: 2 }}>
            Nenhum saldo projetado ainda para esta carteira — só aparece aqui depois de uma
            configuração de monitoramento de saldo (aba Saldos) processar eventos que a envolvam.
          </Alert>
        )}

        {data && data.content.length > 0 && (
          <Box sx={{ overflowX: 'auto' }}>
            <TableContainer sx={{ mt: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Contrato</TableCell>
                    <TableCell>Token ID</TableCell>
                    <TableCell>Saldo</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.content.map((row, index) => (
                    <TableRow key={`${row.contractAddress}-${row.tokenId ?? index}`}>
                      <TableCell>
                        <AddressCell address={row.contractAddress} kind="contract" />
                      </TableCell>
                      <TableCell>{row.tokenId ?? '—'}</TableCell>
                      <TableCell sx={{ fontFamily: 'monospace' }}>{row.balance}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>
        )}

        {data && (
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            onPageChange={setPage}
          />
        )}
      </CardContent>
    </Card>
  )
}
