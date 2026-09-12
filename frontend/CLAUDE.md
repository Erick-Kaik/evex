# Evex Frontend

> Ler primeiro: [`../CLAUDE.md`](../CLAUDE.md) (decisões travadas) e [`../base/indexer-product-draft.md`](../base/indexer-product-draft.md) (produto, seção 6 — UI mínima da PoC).
> Não existe frontend no código base (`base/indexer` é só backend Kotlin) — este módulo é escrito do zero.

## Stack

| Item | Decisão |
|---|---|
| Framework | React 19 |
| Linguagem | TypeScript (strict) |
| Bundler | Vite (via `npm create vite@latest`) |
| HTTP client | Gerado pelo `openapi-generator` (`typescript-axios`) a partir do **mesmo** `.yaml` do backend |
| State / fetching | TanStack Query v5 |
| Estilo | MUI (Material-UI) — ver [`../docs/decisions.md`](../docs/decisions.md) DEC-060, revisa a regra "Tailwind, sem CSS-in-JS" original (`../base/indexer-product-draft.md` §9) por decisão consciente do usuário |
| Roteamento | React Router v7 |
| Testes unitários | Vitest + @testing-library/react + jsdom (DEC-077, E16) — `npm test`. Config em `vitest.config.ts`; helpers em `src/test/`. Mock do cliente gerado via `vi.mock('../api/api', ...)` + `vi.hoisted`. A suíte E2E (`e2e/`, Playwright) cobre fluxo ponta a ponta — papel distinto. |

## Estrutura proposta

```
frontend/src/
├── api/            cliente gerado (typescript-axios) — não editar manualmente
├── features/
│   ├── contracts/  cadastro/listagem de contratos monitorados, upload/colagem de ABI, discovery Blockscout
│   ├── events/      visualização de eventos indexados com filtros
│   ├── balances/    saldos históricos (carteira, token, data)
│   └── webhooks/    configuração de webhooks
├── components/      componentes compartilhados
├── mocks/           modo mock (`npm run dev:mock`) — MSW + dados estáticos, NÃO é parte do produto
└── routes/          React Router v7
```

## Modo mock (`npm run dev:mock`)

Sobe o front **sozinho, sem backend**, com dados de exemplo — para demo/prints da camada visual. Um
Service Worker do [MSW](https://mswjs.io) (`src/mocks/`) intercepta `*/v1/**` + o `control-panel` e
responde do `db` em memória (`src/mocks/db.ts`, mutável — recarregar zera). Ligado por
`import.meta.env.VITE_MOCK === '1'` (`.env.mock`), com import dinâmico em `main.tsx` — o código de
mock **nunca entra no bundle de produção** (dead-code-eliminated quando `VITE_MOCK` ≠ `'1'`). Os
componentes e o cliente gerado rodam sem mudança. Guia de uso com prints: [`../docs/guia-de-uso.md`](../docs/guia-de-uso.md).
**Não use para validar integração** — não fala com rede nem banco.

## Telas da UI mínima da PoC (doc §6)

- Listagem e cadastro de contratos monitorados.
- Visualização de eventos indexados com filtros básicos.
- Tela de saldos históricos (por carteira, token e data).
- Configuração de webhooks.

## Regras não negociáveis (do doc de produto §9)

- **React Query para fetching**: sem `useEffect` para chamadas de API.
  - Exceção (E17/DEC-074): `useServerSentStatus` assina um stream SSE (`EventSource`) — o `useEffect` ali só gere o ciclo de vida da conexão (transporte de push), não faz fetch de dados. O fallback REST continua sendo React Query.
- **Cliente gerado**: nunca escrever chamadas HTTP manualmente; usar o cliente gerado pelo `openapi-generator` a partir do mesmo `.yaml` do backend.
  - Vale para o **backend do Evex**. Os 2 streams SSE (`text/event-stream`) são consumidos via `EventSource` nativo (o generator não modela stream) — ver `src/components/useServerSentStatus.ts`. O `local-env/control-panel` (aba "Demonstração", DEC-075) é outra origem sem spec — `fetch` direto, `src/features/demo/controlPanel.ts`.
- **`size`/`limit`/`page` mandados ao backend respeitam o `minimum`/`maximum` do parâmetro na spec `.yaml`** (E16/DEC-072 complemento): pedir além do `maximum` devolve `400 VALIDATION_ERROR` e quebra a tela em silêncio. Conferir na spec antes de fixar um valor.
- **TypeScript strict**: sem `any` explícito.
- **MUI**: componentes/estilização via `@mui/material` (Emotion por baixo) — Tailwind removido do projeto (DEC-060, E13). Sem misturar os dois.
- **Idioma do código**: inglês. **Idioma da UI/documentação**: português.
- **Sem autenticação na PoC**.

## Variáveis de ambiente (`.env` / `.env.example`)

| Var | Default | Papel |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | URL base do backend Evex (cliente gerado, `src/api/config.ts`). |
| `VITE_ENABLE_DEMO_TAB` | — (não setada) | `'1'` liga a aba **"Demonstração"** (DEC-101) — opt-in explícito, separado da URL abaixo, porque a aba só faz sentido contra um RPC local (dispara scripts de deploy/mint do `local-env/`). Nunca ligar contra uma rede real. |
| `VITE_LOCALENV_CONTROL_URL` | — (não setada) | URL do `local-env/control-panel` (E17/DEC-075). Quando `VITE_ENABLE_DEMO_TAB='1'` **e** o serviço responde ao health check, a aba **"Demonstração"** aparece na navegação e a rota `/demo` funciona. Faltando qualquer uma das duas condições, a aba não existe (nem no `RootLayout`, nem efetivamente no router — o `<DemoPage>` redireciona). Só faz sentido em ambiente local. |
| `VITE_MOCK` | — (não setada) | `'1'` liga o modo mock (`.env.mock`, usado por `npm run dev:mock`). Nunca em produção. |
