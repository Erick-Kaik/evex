import { createBrowserRouter, Navigate } from 'react-router'
import { RootLayout } from './RootLayout'
import { ContractsListPage } from '../features/contracts/ContractsListPage'
import { ContractFormPage } from '../features/contracts/ContractFormPage'
import { WalletsListPage } from '../features/wallets/WalletsListPage'
import { WalletFormPage } from '../features/wallets/WalletFormPage'
import { EventsPage } from '../features/events/EventsPage'
import { BalancesPage } from '../features/balances/BalancesPage'
import { BalanceMonitorFormPage } from '../features/balances/BalanceMonitorFormPage'
import { WebhooksListPage } from '../features/webhooks/WebhooksListPage'
import { WebhookFormPage } from '../features/webhooks/WebhookFormPage'
import { DemoPage } from '../features/demo/DemoPage'

/**
 * Roteamento das telas da UI mínima da PoC (doc §6) — contratos, eventos, saldos (consulta ad-hoc +
 * configuração de monitoramento), webhooks.
 *
 * Os 3 módulos de cadastro (Contratos, Configurações de saldo, Webhooks) seguem o padrão único da
 * E18 (DEC-079, refina a DEC-060): listagem com botão "Cadastrar" → página dedicada de criação
 * (`.../new`); cada linha da listagem tem ações "Ver"/"Editar" → rotas de detalhe (`.../:id`) e
 * edição (`.../:id/edit`); `view`/`edit`/`create` renderizam o mesmo componente de formulário, só
 * mudando o modo (`<ContractFormPage>`, `<BalanceMonitorFormPage>`, `<WebhookFormPage>`). As rotas de
 * configuração de saldo ficam sob `/balances/monitors/*` (não `/balance-monitors/*` top-level) para
 * que o realce da aba "Saldos" no `RootLayout` (que casa por prefixo `/balances`) continue funcionando
 * sem lógica extra — ver DEC-079.
 *
 * A rota `/demo` (aba "Demonstração", E17/DEC-075) fica sempre registrada, mas o `<DemoPage>`
 * redireciona para `/contracts` quando `VITE_LOCALENV_CONTROL_URL` não está setada ou o
 * `local-env/control-panel` não responde ao health check — na prática a tela só existe em ambiente
 * local. A aba correspondente no `RootLayout` é revelada pelo mesmo health check (React Query
 * deduplica a chamada).
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <RootLayout />,
    children: [
      { index: true, element: <Navigate to="/contracts" replace /> },

      { path: 'contracts', element: <ContractsListPage /> },
      { path: 'contracts/new', element: <ContractFormPage mode="create" /> },
      { path: 'contracts/:address', element: <ContractFormPage mode="view" /> },
      { path: 'contracts/:address/edit', element: <ContractFormPage mode="edit" /> },

      { path: 'wallets', element: <WalletsListPage /> },
      { path: 'wallets/new', element: <WalletFormPage mode="create" /> },
      { path: 'wallets/:address', element: <WalletFormPage mode="view" /> },
      { path: 'wallets/:address/edit', element: <WalletFormPage mode="edit" /> },

      { path: 'events', element: <EventsPage /> },

      { path: 'balances', element: <BalancesPage /> },
      { path: 'balances/monitors/new', element: <BalanceMonitorFormPage mode="create" /> },
      { path: 'balances/monitors/:id', element: <BalanceMonitorFormPage mode="view" /> },
      { path: 'balances/monitors/:id/edit', element: <BalanceMonitorFormPage mode="edit" /> },

      { path: 'webhooks', element: <WebhooksListPage /> },
      { path: 'webhooks/new', element: <WebhookFormPage mode="create" /> },
      { path: 'webhooks/:id', element: <WebhookFormPage mode="view" /> },
      { path: 'webhooks/:id/edit', element: <WebhookFormPage mode="edit" /> },

      { path: 'demo', element: <DemoPage /> },
    ],
  },
])
