import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import SearchIcon from '@mui/icons-material/Search'
import AddIcon from '@mui/icons-material/Add'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { useContractAbiVersions } from './useContracts'
import { useRedecodeStatus } from './useRedecodeStatus'
import { AbiRegisterDialog } from './AbiRegisterDialog'
import { AbiSuggestionsDialog } from './AbiSuggestionsDialog'

interface AbiSectionProps {
  address: string
}

/**
 * Seção de ABI da tela de detalhe do contrato (doc §4.1): tabela de versões cadastradas + 2 ações
 * pontuais em modal (E13/DEC-060) — cadastrar nova versão e espiar sugestões de detecção automática.
 */
export function AbiSection({ address }: AbiSectionProps) {
  const abiVersions = useContractAbiVersions(address)
  const redecode = useRedecodeStatus()
  const [registerOpen, setRegisterOpen] = useState(false)
  const [suggestionsOpen, setSuggestionsOpen] = useState(false)

  return (
    <Card variant="outlined" sx={{ mt: 3 }}>
      <CardContent>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
          <Typography variant="h6">ABI</Typography>
          <Stack direction="row" sx={{ gap: 1 }}>
            <Button size="small" startIcon={<SearchIcon />} onClick={() => setSuggestionsOpen(true)}>
              Ver sugestões automáticas
            </Button>
            <Button size="small" variant="contained" startIcon={<AddIcon />} onClick={() => setRegisterOpen(true)}>
              Cadastrar nova versão
            </Button>
          </Stack>
        </Stack>

        {/* O redecode é global (E5/DEC-078), não por contrato — o status não diz de qual contrato.
            Mostrar o indicador em qualquer tela de contrato enquanto um redecode corre é aceitável;
            o texto é genérico de propósito. Some sozinho quando `running` volta a `false`. */}
        {redecode.data?.running && (
          <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mt: 2 }}>
            <CircularProgress size={16} />
            <Typography variant="body2" color="text.secondary">
              Decodificando o histórico… ({redecode.data.processedCount} processados)
            </Typography>
          </Stack>
        )}

        {abiVersions.isPending && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
            Carregando versões...
          </Typography>
        )}
        {abiVersions.isError && <ApiErrorAlert error={abiVersions.error} />}

        {abiVersions.data && abiVersions.data.length === 0 && (
          <Alert severity="info" sx={{ mt: 2 }}>
            Nenhuma versão de ABI cadastrada ainda. Sem ABI, os eventos deste contrato ficam
            indexados como "raw" (não decodificados) — cadastre uma versão para começar a decodificar.
          </Alert>
        )}

        {abiVersions.data && abiVersions.data.length > 0 && (
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small" sx={{ mt: 2 }}>
              <TableHead>
                <TableRow>
                  <TableCell>Bloco inicial</TableCell>
                  <TableCell>Bloco final</TableCell>
                  <TableCell>Origem</TableCell>
                  <TableCell>Storage</TableCell>
                  <TableCell>Cadastrada em</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {abiVersions.data.map((version) => (
                  <TableRow key={version.id}>
                    <TableCell>{version.validFromBlock}</TableCell>
                    <TableCell>{version.validToBlock ?? <Chip size="small" label="vigente" color="success" />}</TableCell>
                    <TableCell>{version.source}</TableCell>
                    <TableCell>{version.storageType}</TableCell>
                    <TableCell>{new Date(version.createdAt).toLocaleString('pt-BR')}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </CardContent>

      <AbiRegisterDialog address={address} open={registerOpen} onClose={() => setRegisterOpen(false)} />
      <AbiSuggestionsDialog address={address} open={suggestionsOpen} onClose={() => setSuggestionsOpen(false)} />
    </Card>
  )
}
