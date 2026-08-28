import { Box, Typography } from '@mui/material'
import { BalanceMonitorSection } from './BalanceMonitorSection'
import { BalanceQuerySection } from './BalanceQuerySection'

/** Saldos históricos (doc §4.4/§6) — consulta de saldos + configurações de monitoramento. */
export function BalancesPage() {
  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h5" sx={{ fontWeight: 600 }}>
        Saldos
      </Typography>
      <Box sx={{ mt: 3 }}>
        <BalanceQuerySection />
      </Box>
      <BalanceMonitorSection />
    </Box>
  )
}
