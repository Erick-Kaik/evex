import { useState } from 'react'
import type { FormEvent } from 'react'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  Link as MuiLink,
  List,
  ListItemButton,
  ListItemText,
  TextField,
  Typography,
} from '@mui/material'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { NetworkSelect } from '../../components/NetworkSelect'
import { useCreateContract, useVerifiedContracts } from './useContracts'
import type { Network } from '../../api/api'

interface ContractFormDialogProps {
  open: boolean
  onClose: () => void
  /** Disparado depois do cadastro com sucesso, com o endereço do contrato criado. */
  onCreated: (address: string) => void
}

/**
 * Cadastro de contrato monitorado em modal (E13/DEC-060 — ação pontual, não mais página própria),
 * com atalho opcional de importação via Blockscout (doc §7) lado a lado.
 */
export function ContractFormDialog({ open, onClose, onCreated }: ContractFormDialogProps) {
  const createContract = useCreateContract()
  const verifiedContracts = useVerifiedContracts()

  const [address, setAddress] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [version, setVersion] = useState('')
  const [network, setNetwork] = useState<Network | ''>('')

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (network === '') {
      return
    }

    createContract.mutate(
      {
        address,
        name,
        description: description || undefined,
        version: version || undefined,
        network,
      },
      {
        onSuccess: (contract) => {
          onCreated(contract.address)
        },
      },
    )
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Novo contrato monitorado</DialogTitle>
      <DialogContent dividers>
        <Grid container spacing={4}>
          <Grid size={{ xs: 12, md: 7 }}>
            <Box component="form" id="contract-form" onSubmit={handleSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <TextField
                label="Endereço"
                required
                fullWidth
                value={address}
                onChange={(event) => setAddress(event.target.value)}
                placeholder="0x..."
                slotProps={{ htmlInput: { style: { fontFamily: 'monospace' } } }}
              />
              <TextField label="Nome" required fullWidth value={name} onChange={(event) => setName(event.target.value)} />
              <TextField
                label="Descrição (opcional)"
                fullWidth
                multiline
                minRows={2}
                value={description}
                onChange={(event) => setDescription(event.target.value)}
              />
              <TextField
                label="Versão (opcional)"
                fullWidth
                placeholder="ex.: 1.0.0"
                value={version}
                onChange={(event) => setVersion(event.target.value)}
              />
              <NetworkSelect required value={network} onChange={setNetwork} />

              {createContract.isError && <ApiErrorAlert error={createContract.error} />}
            </Box>
          </Grid>

          <Grid size={{ xs: 12, md: 5 }}>
            <Typography variant="subtitle2" gutterBottom>
              Importar do Blockscout (opcional)
            </Typography>
            <Divider sx={{ mb: 2 }} />

            {verifiedContracts.isPending && (
              <Typography variant="body2" color="text.secondary">
                Consultando Blockscout...
              </Typography>
            )}

            {verifiedContracts.isError && <ApiErrorAlert error={verifiedContracts.error} />}

            {verifiedContracts.data?.unavailable && (
              <Alert severity="warning">
                Discovery via Blockscout não está disponível nesta instância (nenhum Blockscout
                configurado, ou fora do ar). Preencha o formulário manualmente.
              </Alert>
            )}

            {verifiedContracts.data &&
              !verifiedContracts.data.unavailable &&
              verifiedContracts.data.contracts.length === 0 && (
                <Alert severity="info">
                  Nenhum contrato verificado encontrado no Blockscout. Isso é esperado: o discovery só
                  lista contratos <strong>verificados</strong> — não é um erro. Em ambiente local, rode{' '}
                  <MuiLink
                    href="https://github.com"
                    onClick={(event) => event.preventDefault()}
                    sx={{ cursor: 'default', textDecoration: 'none' }}
                  >
                    <Typography component="code" variant="body2" sx={{ fontFamily: 'monospace' }}>
                      local-env/blockscout/verify-contracts.ts
                    </Typography>
                  </MuiLink>{' '}
                  (via <code>npx tsx blockscout/verify-contracts.ts</code> de dentro de{' '}
                  <code>local-env/</code>) para verificar os contratos de teste antes de tentar de novo.
                </Alert>
              )}

            {verifiedContracts.data && verifiedContracts.data.contracts.length > 0 && (
              <List dense disablePadding>
                {verifiedContracts.data.contracts.map((contract) => (
                  <ListItemButton
                    key={contract.address}
                    onClick={() => {
                      setAddress(contract.address)
                      setName(contract.name ?? contract.address)
                    }}
                    sx={{ border: 1, borderColor: 'divider', borderRadius: 1, mb: 1 }}
                  >
                    <ListItemText
                      primary={contract.name ?? contract.address}
                      secondary={contract.address}
                      slotProps={{
                        primary: { sx: { fontWeight: 500 } },
                        secondary: { sx: { fontFamily: 'monospace', fontSize: '0.75rem' } },
                      }}
                    />
                  </ListItemButton>
                ))}
              </List>
            )}
          </Grid>
        </Grid>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancelar</Button>
        <Button type="submit" form="contract-form" variant="contained" disabled={createContract.isPending}>
          {createContract.isPending ? 'Cadastrando...' : 'Cadastrar'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
