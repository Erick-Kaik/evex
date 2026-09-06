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
import { AddressCell } from '../../components/AddressCell'
import { Pagination } from '../../components/Pagination'
import { useBalances } from '../balances/useBalances'

const PAGE_SIZE = 10

interface ContractBalancesSectionProps {
  address: string
}

/**
 * Saldos deste contrato, na tela de detalhe (`ContractFormPage`) — mesma consulta de
 * `BalanceQuerySection` (`GET /v1/balances`), só que fixando `contractAddress` e sem os demais
 * filtros (o usuário já está "dentro" do contrato). Complementa o card "ABI": ali é possível ver a
 * ABI vigente por bloco, aqui o efeito prático dela — os saldos projetados a partir dos eventos
 * decodificados deste endereço.
 */
export function ContractBalancesSection({ address }: ContractBalancesSectionProps) {
  const [page, setPage] = useState(0)
  const { data, isPending, isError, error } = useBalances({ contractAddress: address }, page, PAGE_SIZE)

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="h6">Saldos deste contrato</Typography>

        {isPending && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
            Carregando...
          </Typography>
        )}
        {isError && <ApiErrorAlert error={error} />}

        {data && data.content.length === 0 && (
          <Alert severity="info" sx={{ mt: 2 }}>
            Nenhum saldo projetado ainda para este contrato — só aparece aqui depois de uma
            configuração de monitoramento de saldo (aba Saldos) processar eventos dele.
          </Alert>
        )}

        {data && data.content.length > 0 && (
          <Box sx={{ overflowX: 'auto' }}>
            <TableContainer sx={{ mt: 2 }}>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Carteira</TableCell>
                    <TableCell>Token ID</TableCell>
                    <TableCell>Saldo</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {data.content.map((row, index) => (
                    <TableRow key={`${row.walletAddress}-${row.tokenId ?? index}`}>
                      <TableCell>
                        <AddressCell address={row.walletAddress} kind="wallet" />
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
