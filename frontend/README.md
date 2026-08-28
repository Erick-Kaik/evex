# Evex Frontend

React 19 + TypeScript (strict) + Vite + MUI (Material-UI). Ver [`CLAUDE.md`](CLAUDE.md) para as
decisões de stack e [`../docs/roadmap.md`](../docs/roadmap.md) para o estado atual das etapas de
frontend (E9/E10/E13).

## Pré-requisitos

- Node.js 22+ (usa `npx` para o Redocly CLI e o `openapi-generator-cli`).
- Java 21+ no PATH — o `openapi-generator-cli` baixa e roda um `.jar` internamente.
- Backend Evex rodando (`docker compose up -d` + `./gradlew bootRun` em `../backend`, porta 8080
  por padrão).

## Cliente da API — bundle + geração

O cliente HTTP (`src/api/`, `typescript-axios`) **nunca é escrito à mão nem commitado** — é gerado
a partir do mesmo `.yaml` do backend, em 2 passos encadeados:

1. `npm run bundle:api` — bundla `../backend/src/main/resources/swagger/base/base.yaml` (spec
   multi-arquivo) num único arquivo resolvido via Redocly CLI, salvo em
   `openapi/evex-api.bundled.yaml` (mesma ferramenta que o backend já usa para o próprio bundle,
   DEC-012 — aqui o frontend bundla por conta própria, sem depender do artefato de build do
   backend).
2. `npm run generate:api` — roda `bundle:api` e então o `openapi-generator` (`typescript-axios`)
   sobre o bundle, escrevendo em `src/api/` (só o código gerado — `api.ts`/`base.ts`/`common.ts`/
   `configuration.ts`/`index.ts` — sem os arquivos de publicação de pacote npm que o gerador cria
   por padrão).

`npm run dev`/`npm run build` já encadeiam `generate:api` automaticamente via `predev`/`prebuild` —
não é preciso rodar manualmente no dia a dia, só se quiser inspecionar o resultado isoladamente.

`openapi/` e `src/api/` (exceto `src/api/config.ts`, o único arquivo handwritten desse diretório —
configura o `basePath` do cliente gerado) estão no `.gitignore`.

## Rodando localmente

```bash
npm install
npm run dev
```

Abre em `http://localhost:5173`. O cliente aponta por padrão para `http://localhost:8080`
(`VITE_API_BASE_URL`, ver `.env.example`) — funciona cross-origin de verdade graças ao CORS
configurado no backend desde a E8.1 (`indexer.cors.allowed-origins`, default
`http://localhost:5173`). O proxy de dev em `vite.config.ts` (`/v1`, `/actuator` →
`localhost:8080`) é uma conveniência **opcional**, não necessária para o caso principal.

## Outros scripts

- `npm run build` — `tsc -b && vite build` (inclui `generate:api` via `prebuild`).
- `npm run lint` — ESLint (`@typescript-eslint/no-explicit-any` ativo via `tseslint.configs.recommended`).
- `npm run preview` — serve o build de produção localmente.
