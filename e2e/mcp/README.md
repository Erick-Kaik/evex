# Playwright MCP — E2E exploratório dirigido pelo Claude Code

> Complementa a suíte automatizada (`e2e/tests/*.spec.ts`), não substitui. A suíte é a rede de
> segurança repetível (CI, regressão); o MCP é pra o **Claude dirigir o navegador ao vivo** contra a
> stack real — explorar um fluxo novo, reproduzir um bug, ou fazer um walkthrough dos casos de teste
> com você assistindo a tela.

## O que é

[`@playwright/mcp`](https://github.com/microsoft/playwright-mcp) é um servidor MCP que expõe um
navegador Chromium como ferramentas pro Claude Code (`browser_navigate`, `browser_click`,
`browser_type`, `browser_snapshot`, ...). O Claude "vê" a página pela árvore de acessibilidade e
interage como um usuário. **É Playwright dirigido por IA** — diferente da suíte `.spec.ts`, que é
Playwright puro e determinístico.

## Setup (uma vez)

Já configurado em [`../../.mcp.json`](../../.mcp.json) (escopo do projeto):

```json
{
  "mcpServers": {
    "playwright": {
      "command": "npx",
      "args": ["-y", "@playwright/mcp@latest", "--browser=chromium", "--viewport-size=1280,800", "--output-dir=./e2e/.mcp-output"]
    }
  }
}
```

- **Headed por padrão** (sem `--headless`) — a janela do Chromium aparece na tela (WSLg no Windows 11
  renderiza GUI Linux nativamente). É assim que você "vê a interação".
- Ao abrir o Claude Code neste repo pela primeira vez depois deste commit, ele pede pra **aprovar** o
  servidor `playwright`. Aprove. Confira com `/mcp` (deve listar `playwright` como `connected`).
- O `npx -y` baixa o `@playwright/mcp` na 1ª vez. O Chromium é o mesmo que a suíte usa
  (`npx playwright install chromium`, feito uma vez).

## Como usar

1. Suba a stack (igual pra suíte — ver [`../README.md`](../README.md) §2): Hardhat `--hostname 0.0.0.0`,
   `local-env/scripts/up.sh`, backend na IDE, `blockscout-up.sh` se for testar discovery.
2. No Claude Code, peça o walkthrough. Ex.:
   > "Usando o playwright MCP, faça o fluxo de cadastro de contrato + ABI colada em http://localhost:5173
   > e me diga se a versão aparece na tabela."
3. A janela do Chromium abre e o Claude clica/digita/navega enquanto você assiste. Traces e
   screenshots caem em `e2e/.mcp-output/` (git-ignored).

## MCP vs. suíte automatizada — quando usar qual

| | Suíte `.spec.ts` (`./scripts/run.sh`) | Playwright MCP |
|---|---|---|
| Executor | Playwright puro, determinístico | Claude Code dirigindo |
| Repetível / CI | ✅ | ❌ (exploratório) |
| Escrever/manter | código nos specs | linguagem natural na hora |
| Bom pra | regressão, os 8 fluxos fixos, gate de etapa | investigar bug, fluxo novo ainda sem spec, demo ao vivo |
| Ver na tela | `--headed` / `--ui` | sempre (headed) |

Fluxo saudável: explora com o MCP → quando o fluxo estabiliza, vira um `.spec.ts` na suíte.

## Ver a suíte automatizada rodando na tela (sem MCP)

```bash
cd e2e
./scripts/run.sh --headed     # roda os specs com o navegador visível
./scripts/run.sh --ui         # Playwright UI: assiste, pausa, time-travel, pick locator
```
