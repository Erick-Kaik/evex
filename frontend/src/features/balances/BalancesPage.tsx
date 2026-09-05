import { Box, Typography } from '@mui/material'
import { BalanceMonitorListSection } from './BalanceMonitorListSection'
import { BalanceQuerySection } from './BalanceQuerySection'

/**
 * Saldos históricos (doc §4.4/§6) — consulta ad-hoc de saldos (`BalanceQuerySection`, não é CRUD,
 * fora do padrão E18) + listagem de configurações de monitoramento (`BalanceMonitorListSection`,
 * segue o padrão E18 — cadastro/detalhe em páginas dedicadas sob `/balances/monitors/*`).
 */
export function BalancesPage() {
  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600 }}>
        Saldos
      </Typography>
      <Box sx={{ mt: 3 }}>
        <BalanceQuerySection />
      </Box>
      <BalanceMonitorListSection />
    </Box>
  )
}
