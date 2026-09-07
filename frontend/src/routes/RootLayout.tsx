import { NavLink, Outlet, useLocation } from 'react-router'
import { AppBar, Box, Tab, Tabs, Toolbar, Typography } from '@mui/material'
import { useControlPanelAvailable } from '../features/demo/useControlPanel'

const NAV_LINKS = [
  { to: '/contracts', label: 'Contratos' },
  { to: '/wallets', label: 'Carteiras' },
  { to: '/events', label: 'Eventos' },
  { to: '/balances', label: 'Saldos' },
  { to: '/webhooks', label: 'Webhooks' },
  { to: '/enrichment-profiles', label: 'Enriquecimentos' },
  { to: '/entity-models', label: 'Modelos' },
]

/** Layout raiz — barra de navegação com as 4 telas da UI mínima da PoC (doc §6). */
export function RootLayout() {
  const location = useLocation()
  // Aba "Demonstração" (E17/DEC-075): só aparece quando `VITE_LOCALENV_CONTROL_URL` está setada e o
  // `local-env/control-panel` responde ao health check. Fora do ambiente local, a aba não existe.
  const demoAvailable = useControlPanelAvailable()
  const navLinks = demoAvailable ? [...NAV_LINKS, { to: '/demo', label: 'Demonstração' }] : NAV_LINKS

  const currentTab = navLinks.find((link) => location.pathname.startsWith(link.to))?.to ?? false

  return (
    <Box sx={{ minHeight: '100%', bgcolor: 'background.default' }}>
      <AppBar position="static" color="default" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Toolbar sx={{ gap: { xs: 1.5, sm: 4 }, minWidth: 0 }}>
          <Typography variant="h6" sx={{ fontWeight: 600, flexShrink: 0 }}>
            Evex
          </Typography>
          <Tabs
            value={currentTab}
            textColor="inherit"
            indicatorColor="primary"
            variant="scrollable"
            scrollButtons="auto"
            allowScrollButtonsMobile
            sx={{ flex: 1, minWidth: 0 }}
          >
            {navLinks.map((link) => (
              <Tab key={link.to} label={link.label} value={link.to} component={NavLink} to={link.to} />
            ))}
          </Tabs>
        </Toolbar>
      </AppBar>
      <Box component="main">
        <Outlet />
      </Box>
    </Box>
  )
}
