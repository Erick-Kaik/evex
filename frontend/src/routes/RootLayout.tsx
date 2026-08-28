import { NavLink, Outlet, useLocation } from 'react-router'
import { AppBar, Box, Tab, Tabs, Toolbar, Typography } from '@mui/material'

const NAV_LINKS = [
  { to: '/contracts', label: 'Contratos' },
  { to: '/events', label: 'Eventos' },
  { to: '/balances', label: 'Saldos' },
  { to: '/webhooks', label: 'Webhooks' },
]

/** Layout raiz — barra de navegação com as 4 telas da UI mínima da PoC (doc §6). */
export function RootLayout() {
  const location = useLocation()
  const currentTab = NAV_LINKS.find((link) => location.pathname.startsWith(link.to))?.to ?? false

  return (
    <Box sx={{ minHeight: '100%', bgcolor: 'background.default' }}>
      <AppBar position="static" color="default" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 4 }}>
          <Typography variant="h6" sx={{ fontWeight: 600 }}>
            Evex
          </Typography>
          <Tabs value={currentTab} textColor="inherit" indicatorColor="primary">
            {NAV_LINKS.map((link) => (
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
