import { createBrowserRouter, Navigate } from 'react-router'
import { RootLayout } from './RootLayout'
import { ContractsListPage } from '../features/contracts/ContractsListPage'
import { ContractFormPage } from '../features/contracts/ContractFormPage'
import { AbiEntriesPage } from '../features/contracts/AbiEntriesPage'
import { WalletsListPage } from '../features/wallets/WalletsListPage'
import { WalletFormPage } from '../features/wallets/WalletFormPage'
import { EventsPage } from '../features/events/EventsPage'
import { BalancesPage } from '../features/balances/BalancesPage'
import { BalanceMonitorFormPage } from '../features/balances/BalanceMonitorFormPage'
import { WebhooksListPage } from '../features/webhooks/WebhooksListPage'
import { WebhookFormPage } from '../features/webhooks/WebhookFormPage'
import { EnrichmentProfilesListPage } from '../features/enrichment/EnrichmentProfilesListPage'
import { EnrichmentProfileFormPage } from '../features/enrichment/EnrichmentProfileFormPage'
import { EntityModelsListPage } from '../features/models/EntityModelsListPage'
import { EntityModelFormPage } from '../features/models/EntityModelFormPage'
import { DemoPage } from '../features/demo/DemoPage'

/**
 * Roteamento das telas da UI mínima da PoC (doc §6) — contratos, eventos, saldos (consulta ad-hoc +
 * configuração de monitoramento), webhooks, e os 2 recursos novos da E19 (Perfis de Enriquecimento e
 * Modelos de Entidade, DEC-084) que os webhooks podem referenciar pra enriquecer o payload.
 *
 * Os módulos de cadastro (Contratos, Configurações de saldo, Webhooks, Carteiras, Perfis de
 * Enriquecimento, Modelos de Entidade) seguem o padrão único da E18 (DEC-079, refina a DEC-060):
 * listagem com botão "Cadastrar" → página dedicada de criação (`.../new`); cada linha da listagem tem
 * ações "Ver"/"Editar" → rotas de detalhe (`.../:id`) e edição (`.../:id/edit`); `view`/`edit`/`create`
 * renderizam o mesmo componente de formulário, só mudando o modo (`<ContractFormPage>`,
 * `<BalanceMonitorFormPage>`, `<WebhookFormPage>`, `<EnrichmentProfileFormPage>`,
 * `<EntityModelFormPage>`). As rotas de configuração de saldo ficam sob `/balances/monitors/*` (não
 * `/balance-monitors/*` top-level) para que o realce da aba "Saldos" no `RootLayout` (que casa por
 * prefixo `/balances`) continue funcionando sem lógica extra — ver DEC-079.
 *
 * `contracts/:address/abi-entries` (E24) é a tela do Explorador de ABI — somente leitura, sem
 * `new`/`edit` (não é um CRUD, é uma visão derivada das versões de ABI já cadastradas); acessada por
 * um botão em cada linha da tabela de versões (`AbiSection`), não por navegação direta na sidebar.
 *
 * A rota `/demo` (aba "Demonstração", E17/DEC-075) fica sempre registrada, mas o `<DemoPage>`
 * redireciona para `/contracts` quando `VITE_ENABLE_DEMO_TAB` não está `'1'` (DEC-101 — opt-in
 * explícito, a aba só faz sentido contra um RPC local), `VITE_LOCALENV_CONTROL_URL` não está setada,
 * ou o `local-env/control-panel` não responde ao health check — na prática a tela só existe em
 * ambiente local. A aba correspondente no `RootLayout` é revelada pelas mesmas condições (React
 * Query deduplica a chamada de health check).
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
      { path: 'contracts/:address/abi-entries', element: <AbiEntriesPage /> },

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

      { path: 'enrichment-profiles', element: <EnrichmentProfilesListPage /> },
      { path: 'enrichment-profiles/new', element: <EnrichmentProfileFormPage mode="create" /> },
      { path: 'enrichment-profiles/:id', element: <EnrichmentProfileFormPage mode="view" /> },
      { path: 'enrichment-profiles/:id/edit', element: <EnrichmentProfileFormPage mode="edit" /> },

      { path: 'entity-models', element: <EntityModelsListPage /> },
      { path: 'entity-models/new', element: <EntityModelFormPage mode="create" /> },
      { path: 'entity-models/:id', element: <EntityModelFormPage mode="view" /> },
      { path: 'entity-models/:id/edit', element: <EntityModelFormPage mode="edit" /> },

      { path: 'demo', element: <DemoPage /> },
    ],
  },
])
