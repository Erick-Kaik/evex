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
└── routes/          React Router v7
```

## Telas da UI mínima da PoC (doc §6)

- Listagem e cadastro de contratos monitorados.
- Visualização de eventos indexados com filtros básicos.
- Tela de saldos históricos (por carteira, token e data).
- Configuração de webhooks.

## Regras não negociáveis (do doc de produto §9)

- **React Query para fetching**: sem `useEffect` para chamadas de API.
- **Cliente gerado**: nunca escrever chamadas HTTP manualmente; usar o cliente gerado pelo `openapi-generator` a partir do mesmo `.yaml` do backend.
- **TypeScript strict**: sem `any` explícito.
- **MUI**: componentes/estilização via `@mui/material` (Emotion por baixo) — Tailwind removido do projeto (DEC-060, E13). Sem misturar os dois.
- **Idioma do código**: inglês. **Idioma da UI/documentação**: português.
- **Sem autenticação na PoC**.
