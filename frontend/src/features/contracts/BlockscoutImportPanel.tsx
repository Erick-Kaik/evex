import { useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  Link as MuiLink,
  List,
  ListItemButton,
  ListItemText,
  Stack,
  Typography,
} from '@mui/material'
import type { BlockscoutContract } from '../../api/api'
import { ApiErrorAlert } from '../../components/ApiErrorAlert'
import { useVerifiedContracts } from './useContracts'

interface BlockscoutImportPanelProps {
  /** Preenche endereço + nome do formulário de cadastro. */
  onPick: (address: string, name: string) => void
}

type Kind = 'PROXY' | 'IMPLEMENTATION' | 'STANDALONE'

/** Valor ausente/desconhecido de `kind` é tratado como STANDALONE (contrato da DEC-104). */
function normalizeKind(kind: string | undefined): Kind {
  return kind === 'PROXY' || kind === 'IMPLEMENTATION' ? kind : 'STANDALONE'
}

const KIND_CHIP: Record<Exclude<Kind, 'STANDALONE'>, { label: string; color: 'info' | 'warning' }> = {
  PROXY: { label: 'Proxy', color: 'info' },
  IMPLEMENTATION: { label: 'Implementação', color: 'warning' },
}

/**
 * Coluna "Importar do Blockscout" do cadastro de contrato (doc §7, DEC-104). Cada item mostra o `kind`
 * (proxy/implementação) e os `warnings` do backend como vieram (texto PT-BR pronto). Importar uma
 * IMPLEMENTATION com exatamente 1 proxy pré-preenche o endereço do PROXY (os eventos são emitidos por
 * ele) e avisa; com 2+ proxies o usuário escolhe qual; com 0 proxies ou STANDALONE preenche o próprio
 * endereço. Nunca bloqueia o cadastro.
 */
export function BlockscoutImportPanel({ onPick }: BlockscoutImportPanelProps) {
  const verifiedContracts = useVerifiedContracts()
  const [choosing, setChoosing] = useState<BlockscoutContract | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  function pickContract(contract: BlockscoutContract) {
    const name = contract.name ?? contract.address
    const proxies = contract.proxyAddresses ?? []

    if (normalizeKind(contract.kind) === 'IMPLEMENTATION' && proxies.length === 1) {
      setChoosing(null)
      setNotice(
        `Este item é a implementação de um proxy: o endereço do proxy (${proxies[0]}) foi preenchido no lugar do da implementação, porque os eventos são emitidos pelo proxy.`,
      )
      onPick(proxies[0], name)
      return
    }

    if (normalizeKind(contract.kind) === 'IMPLEMENTATION' && proxies.length > 1) {
      // Não pré-preenche o endereço em silêncio: o usuário escolhe o proxy. O nome já ajuda.
      setChoosing(contract)
      setNotice(null)
      return
    }

    setChoosing(null)
    setNotice(null)
    onPick(contract.address, name)
  }

  function pickProxy(contract: BlockscoutContract, proxyAddress: string) {
    setChoosing(null)
    setNotice(
      `Endereço do proxy ${proxyAddress} preenchido (a implementação ${contract.address} não emite os eventos).`,
    )
    onPick(proxyAddress, contract.name ?? contract.address)
  }

  return (
    <>
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

      {notice && (
        <Alert severity="info" sx={{ mb: 1 }} role="status">
          {notice}
        </Alert>
      )}

      {verifiedContracts.data && verifiedContracts.data.contracts.length > 0 && (
        <List dense disablePadding>
          {verifiedContracts.data.contracts.map((contract) => (
            <DiscoveryItem
              key={contract.address}
              contract={contract}
              choosing={choosing?.address === contract.address}
              onPick={() => pickContract(contract)}
              onPickProxy={(proxy) => pickProxy(contract, proxy)}
            />
          ))}
        </List>
      )}
    </>
  )
}

interface DiscoveryItemProps {
  contract: BlockscoutContract
  choosing: boolean
  onPick: () => void
  onPickProxy: (proxyAddress: string) => void
}

function DiscoveryItem({ contract, choosing, onPick, onPickProxy }: DiscoveryItemProps) {
  const kind = normalizeKind(contract.kind)
  const warnings = contract.warnings ?? []
  const proxies = contract.proxyAddresses ?? []

  return (
    <Box sx={{ mb: 1 }}>
      <ListItemButton onClick={onPick} sx={{ border: 1, borderColor: 'divider', borderRadius: 1 }}>
        <ListItemText
          primary={
            <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
              <span>{contract.name ?? contract.address}</span>
              {kind !== 'STANDALONE' && (
                <Chip
                  size="small"
                  label={KIND_CHIP[kind].label}
                  color={KIND_CHIP[kind].color}
                  variant="outlined"
                />
              )}
            </Stack>
          }
          secondary={contract.address}
          slotProps={{
            primary: { component: 'div', sx: { fontWeight: 500 } },
            secondary: { sx: { fontFamily: 'monospace', fontSize: '0.75rem' } },
          }}
        />
      </ListItemButton>

      {warnings.map((warning, index) => (
        <Alert key={`${index}-${warning}`} severity={kind === 'IMPLEMENTATION' ? 'warning' : 'info'} sx={{ mt: 0.5 }}>
          {warning}
        </Alert>
      ))}

      {choosing && (
        <Box
          role="group"
          aria-label={`Escolha o proxy de ${contract.name ?? contract.address}`}
          sx={{ mt: 0.5, p: 1, border: 1, borderColor: 'warning.main', borderRadius: 1 }}
        >
          <Typography variant="body2" sx={{ mb: 1 }}>
            Esta implementação é usada por {proxies.length} proxies. Escolha qual proxy cadastrar:
          </Typography>
          <Stack sx={{ gap: 0.5 }}>
            {proxies.map((proxy) => (
              <Button
                key={proxy}
                size="small"
                variant="outlined"
                onClick={() => onPickProxy(proxy)}
                sx={{ fontFamily: 'monospace', justifyContent: 'flex-start' }}
              >
                {proxy}
              </Button>
            ))}
          </Stack>
        </Box>
      )}
    </Box>
  )
}
