# Evex — guia para agentes de IA (qualquer ferramenta)

Este arquivo é a versão **agnóstica de ferramenta** das instruções deste repositório — vale pra
qualquer assistente de IA que abra este projeto (Claude Code, Cursor, Copilot, Codex, ou qualquer
outro). Se a ferramenta for o Claude Code especificamente, leia também [`CLAUDE.md`](CLAUDE.md), que
tem o mesmo conteúdo-base mais convenções específicas dessa ferramenta (agentes especializados em
`.claude/agents/`, uso de subagentes). Os dois arquivos **não devem divergir** em decisão de produto —
se um for atualizado com uma regra nova de projeto, o outro também precisa ser.

## O que é o Evex

API de indexação de eventos EVM genérica e configurável, para redes privadas Besu — ver
[`README.md`](README.md) (visão geral + módulos) e [`base/indexer-product-draft.md`](base/indexer-product-draft.md)
(problema, funcionalidades e escopo completos).

## ⚑ Checklist de início de sessão (obrigatório, primeira ação)

Antes de responder qualquer coisa sobre o projeto ou tocar em código:

1. Ler [`docs/roadmap.md`](docs/roadmap.md) inteiro — qual etapa está em andamento/próxima, o que já
   foi feito, notas de execução da última etapa concluída.
2. Ler as entradas mais recentes de [`docs/decisions.md`](docs/decisions.md) — decisões técnicas já
   fechadas, pra não propor de novo algo já decidido (ou pior, contradizer sem perceber).
3. Se a tarefa for em `backend/` ou `frontend/`, ler também o `CLAUDE.md` daquele módulo (mesmo que sua
   ferramenta não seja o Claude Code — o conteúdo é sobre o projeto, não sobre a ferramenta).
4. Reportar em 1-2 frases onde o projeto parou antes de propor o que fazer a seguir — não assumir que
   quem está pedindo lembra o estado exato.

Ao terminar qualquer unidade de trabalho: atualizar `docs/roadmap.md` (status + notas de execução) e,
se houve decisão técnica não trivial, `docs/decisions.md` (nova entrada). Essa regra vale pra qualquer
sessão/agente, independente da ferramenta.

## Documentos de referência (mesma lista do `README.md`)

| Doc | Papel |
|---|---|
| [`base/indexer-product-draft.md`](base/indexer-product-draft.md) | Fonte de verdade de **produto**. |
| [`backend/CLAUDE.md`](backend/CLAUDE.md) | Decisões técnicas e estrutura do backend. |
| [`frontend/CLAUDE.md`](frontend/CLAUDE.md) | Decisões técnicas e estrutura do frontend. |
| [`docs/roadmap.md`](docs/roadmap.md) | **Onde estamos**: etapas, status, critério de aceite. |
| [`docs/decisions.md`](docs/decisions.md) | **Por que decidimos assim**: log histórico (ADR). |
| [`docs/guia-de-uso.md`](docs/guia-de-uso.md) | **Como usar**: guia por cenário, com prints. |

## Não duplicar trabalho já feito

`docs/roadmap.md` lista, etapa por etapa, o que já foi implementado e validado. Antes de propor uma
mudança de arquitetura ou decisão técnica, confira se ela já não foi fechada em `docs/decisions.md` —
o histórico existe justamente pra evitar retrabalho ou contradição silenciosa.

## Regras gerais (válidas para todo o repositório)

- **Idioma do código**: inglês para identificadores (variáveis, funções, classes, pacotes).
- **Idioma dos comentários/docstrings inline e commits**: português — mesmo idioma da documentação
  solta (`.md`). Só os identificadores ficam em inglês.
- **Idioma da documentação**: português.
- **Sem autenticação/autorização** nesta fase da PoC — nenhuma camada de auth.
- **Instância por rede**: uma instância do Evex por cliente/ambiente; segregação interna por campo
  `network` (`MAINNET` | `TESTNET`).
- **OpenAPI first** (backend): o `.yaml` é a fonte de verdade — nunca escrever controllers ou clientes
  HTTP manualmente (exceções pontuais documentadas em `backend/CLAUDE.md`).
- **Cliente gerado** (frontend): nunca escrever chamadas HTTP manualmente — sempre o cliente gerado a
  partir da mesma spec do backend.

## Especialização por sub-tarefa

Se a sua ferramenta suporta agentes/perfis especializados, este repositório já define papéis
recomendados (arquitetura hexagonal, integração blockchain/Web3j, backend Kotlin, frontend React,
revisão) — ver a tabela em [`CLAUDE.md`](CLAUDE.md#agentes-especializados-claudeagents) pra saber qual
escopo cabe a cada um, mesmo que sua ferramenta não use o mecanismo exato de subagente do Claude Code.
Se não houver esse recurso, siga o mesmo escopo como divisão de responsabilidade dentro da própria
sessão.
