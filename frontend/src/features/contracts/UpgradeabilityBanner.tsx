import { Alert, AlertTitle, Box, Chip, Stack, Typography } from '@mui/material'
import { useContractUpgradeability } from './useContracts'

interface UpgradeabilityBannerProps {
  address: string
}

/**
 * Banner de detecção de proxy upgradável UUPS/ERC-1822 (E28/DEC-099), no Explorador de ABI
 * (`AbiEntriesPage`). Busca `GET /v1/contracts/{address}/upgradeability` e só aparece quando
 * `isUpgradeable === true` — mesmo racional condicional de `ContractLineageSection` (E29): um
 * contrato normal não mostra nada, não é um estado "vazio" explícito. Pendente/erro também não
 * renderizam nada aqui (diferente de `ContractLineageSection`, que mostra erro de rede) porque esta
 * informação é só um complemento da tela, não o motivo dela existir — um erro nessa consulta não
 * deveria competir por atenção com a tabela de ABI, que é o conteúdo principal da página.
 *
 * Quando `availableUpgradeMethods` não vem vazio, lista os métodos como `Chip`s — é "o caminho que o
 * usuário chamaria" (motivação original da E28), puramente informativo. Nenhum botão/link de ação: o
 * Evex não assina nem envia transação de upgrade, e o texto deixa isso explícito de propósito, pra
 * não parecer um convite a "clicar aqui pra atualizar".
 */
export function UpgradeabilityBanner({ address }: UpgradeabilityBannerProps) {
  const { data, isPending, isError } = useContractUpgradeability(address)

  if (isPending || isError || !data || !data.isUpgradeable) {
    return null
  }

  return (
    <Alert severity="info" sx={{ mt: 3 }}>
      <AlertTitle>Contrato upgradeável (UUPS/ERC-1822)</AlertTitle>
      <Typography variant="body2">
        O ABI vigente deste contrato indica que ele implementa o padrão de proxy upgradável
        UUPS/ERC-1822 — a lógica pode ser substituída no futuro sem trocar o endereço do contrato
        (diferente de um redeploy, DEC-096/E29).
      </Typography>

      {data.availableUpgradeMethods.length > 0 && (
        <Box sx={{ mt: 1 }}>
          <Typography variant="body2" sx={{ mb: 0.5 }}>
            Métodos de upgrade declarados no ABI:
          </Typography>
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 0.5 }}>
            {data.availableUpgradeMethods.map((method) => (
              <Chip key={method} size="small" label={method} variant="outlined" />
            ))}
          </Stack>
        </Box>
      )}

      <Typography variant="caption" color="text.secondary" sx={{ mt: 1, display: 'block' }}>
        Isto é só informativo — o Evex não executa nem oferece um jeito de chamar o upgrade.
      </Typography>
    </Alert>
  )
}
