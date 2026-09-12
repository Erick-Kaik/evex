import {
  Card,
  CardContent,
  Chip,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { AddressCell } from '../../components/AddressCell'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { useContractLineage } from './useContracts'

interface ContractLineageSectionProps {
  address: string
}

/**
 * Linhagem de "contratos redeployados" (E29/DEC-096/DEC-097), na tela de detalhe (`ContractFormPage`)
 * — mesmo padrão visual de `ContractBalancesSection`. Busca `GET /v1/contracts/{address}/lineage` e
 * só renderiza a seção quando a linhagem tem **mais de 1** contrato: uma linhagem de tamanho 1
 * (contrato sem `lineageId` — cadastrado antes da E29 — ou nunca envolvido num redeploy) não é
 * informação útil pro usuário, então a seção simplesmente não aparece (diferente de
 * `ContractBalancesSection`, que sempre mostra o card com um estado "vazio" explícito).
 *
 * Cada linha usa `<AddressCell kind="contract">` — mesmo componente/padrão de navegação já usado nas
 * outras listagens do projeto (eventos, saldos) — pra linkar pro detalhe do respectivo contrato.
 */
export function ContractLineageSection({ address }: ContractLineageSectionProps) {
  const { data, isPending, isError, error } = useContractLineage(address)

  if (isPending) {
    return null
  }

  if (isError) {
    return (
      <Card variant="outlined" sx={{ mt: 3 }}>
        <CardContent>
          <Typography variant="h6">Linhagem</Typography>
          <ApiErrorAlert error={error} />
        </CardContent>
      </Card>
    )
  }

  if (!data || data.length <= 1) {
    return null
  }

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Typography variant="h6">Linhagem</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
          Este contrato faz parte de uma linhagem de {data.length} redeploys (contrato novo, endereço
          novo, mesma linhagem lógica de produto — diferente de um proxy upgradável, que mantém o
          mesmo endereço).
        </Typography>

        <TableContainer sx={{ mt: 2 }}>
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
              {data.map((contract) => (
                <TableRow key={contract.id} hover>
                  <TableCell>
                    <AddressCell address={contract.address} kind="contract" />
                  </TableCell>
                  <TableCell>
                    {contract.name}
                    {contract.address.toLowerCase() === address.toLowerCase() && (
                      <Chip size="small" label="Atual" color="primary" variant="outlined" sx={{ ml: 1 }} />
                    )}
                  </TableCell>
                  <TableCell>{contract.network}</TableCell>
                  <TableCell>{contract.status}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </CardContent>
    </Card>
  )
}
