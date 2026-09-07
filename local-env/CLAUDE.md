# Evex — Ambiente de teste local

> Ler primeiro: [`../CLAUDE.md`](../CLAUDE.md) (decisões travadas) e [`../docs/decisions.md`](../docs/decisions.md) DEC-010/DEC-052 a DEC-057 (contexto de por que este módulo existe, por que não é o repo Hardhat da empresa, e as descobertas reais feitas rodando contra um Blockscout de verdade) e DEC-059 (Blockscout e UI do scaffold-eth-2 viraram opt-in, eventos disparados via script).

## O que é

Ambiente local para validar o Evex ponta a ponta contra uma rede EVM real — não só fixtures/mocks: nó
Hardhat + contratos de teste + Blockscout real (para exercitar a E6, discovery/detecção automática) +
UIs prontas pra disparar eventos manualmente (mint, etc.), usado pra destravar a **E12** do roadmap.
**Validado de ponta a ponta** (não só estruturado) — ver `README.md` seção 4/5 e `docs/decisions.md`
DEC-054/DEC-057.

**Não é parte do produto entregável.** Nenhum cliente BBChain recebe nada deste módulo — ele existe só
para desenvolvimento e demonstração do Evex. Por isso vive fora de `backend/`/`frontend/`, como módulo
próprio, e não tem agente especializado dedicado em `.claude/agents/` (é tratado ad-hoc pela sessão
principal quando a etapa do roadmap pedir).

## Stack

| Peça | Ferramenta | Papel |
|---|---|---|
| Nó EVM local | Hardhat, via [scaffold-eth-2](https://github.com/scaffold-eth/scaffold-eth-2) clonado à parte em `local-env-scaffold-eth-2/` (**não vendorizado**) | nó persistente (`yarn chain`, RPC `127.0.0.1:8545`, chainId **31337**) + pipeline de deploy via `rocketh` (`yarn deploy`) — sempre fora do `docker-compose.yml`/scripts, de propósito (ver README §3.1). A UI de disparo de eventos em `/debug` (gera botão pra cada função da ABI) continua disponível mas não é mais o caminho padrão — ver `seed/emit-events.ts` (DEC-059) |
| Explorer / discovery | [Blockscout](https://github.com/blockscout/blockscout) **real**, clonado à parte em `local-env-blockscout/` (**não vendorizado**), buildado do source (v11.2.8) — a imagem publicada (`latest`/`master`) tem um bug de SSL sem correção disponível como imagem (DEC-055/DEC-056) | discovery/ABI/fallback de interface da E6, todos validados contra instância real (DEC-057), não mock. **Opt-in** desde a DEC-059 (profile `blockscout` do compose, `scripts/blockscout-up.sh`/`blockscout-down.sh`) — é a peça mais pesada do ambiente, só sobe quando for testar E6 especificamente |
| Contratos de teste | `contracts/` (versionados aqui, colados dentro do projeto Hardhat do scaffold-eth-2 na hora de deployar) | `TestERC20`/`TestERC721`/`TestERC1155` (wrappers com `mint`/`safeMint`/`mintBatch` públicos sobre OpenZeppelin) + `TestEventEmitter` (contrato 100% customizado, eventos não-padrão) + `TestUpgradeableVaultV1`/`V2`/`Proxy` (proxy UUPS/ERC1967 real, pra validar ABI versionada por faixa de blocos — cenário 11 do guia de uso, ver seção README §3.9) + `TestComputer` (E19 — peças trocáveis/`processingPower`, cenário de validação oficial dos eventos enriquecidos, README §3.10) + `TestGameLibrary` (E25 — jogos que só rodam com poder suficiente, cross-contract real com `TestComputer` + erro customizado `InsufficientPower`, cenário de validação do Modelo de Entidade) |
| Disparo de eventos | `seed/emit-events.ts` (ethers direto, assina com a conta deployer padrão do Hardhat) | substitui a UI `/debug` do scaffold-eth-2 como caminho padrão (DEC-059) — mint/safeMint/mintBatch/createWidget/etc. via script, sem precisar de um Next.js dev server no ar. `seed/deploy-upgradeable-vault.ts` + `seed/upgrade-upgradeable-vault.ts` cobrem o fluxo do cofre upgradeável (deploy+init em V1, depois upgrade pra V2); `seed/computer-vault-scenario.ts` cobre o cenário Computador+Cofre da E19 (troca de peças em blocos espaçados via `hardhat_mine` + depósito/saque proporcional); `seed/change-computer-power.ts` faz um ajuste avulso de `processingPower` num computador já criado (positivo deposita, negativo saca), sem repetir o roteiro fixo; `seed/deploy-game-library.ts` + `seed/game-library-scenario.ts` cobrem o cenário de validação do Modelo de Entidade da E25 (jogos que só rodam com poder suficiente, cross-contract real + erro customizado) |
| Control-panel (E17) | `control-panel/server.ts` (HTTP mínimo, só built-ins do Node, roda via `tsx`) | serviço que a aba **"Demonstração"** do frontend chama pra disparar `deploy`/`emit-events`/`register-contracts`/`deploy-vault`/`upgrade-vault`/`computer-vault-scenario`/`change-computer-power`/`deploy-game-library`/`game-library-scenario` sem abrir terminal (DEC-075). Escuta `127.0.0.1:8899`, `GET /actions` descreve os botões + o comando exato, `POST /run/<id>` faz `spawn` do script e faz stream do stdout via SSE. Sobe/derruba junto com `up.sh`/`down.sh`. **Fora do `backend/` de propósito** (execução de shell num backend sem auth = superfície de RCE; "local-env não é produto") |
| Orquestração | `docker-compose.yml` (Postgres do Evex sempre; Blockscout atrás do profile `blockscout`) + `scripts/up.sh`/`down.sh` (deploy + eventos de teste + docker + frontend do Evex) + `scripts/blockscout-up.sh`/`blockscout-down.sh` (Blockscout, à parte, sob demanda) | reduz o dia a dia a 1 terminal pro nó Hardhat + 1 comando pro resto, mantendo leve por padrão (DEC-059) — backend do Evex fica de fora de propósito (debug/hot-reload na IDE), UI do scaffold-eth-2 e Blockscout ficam opt-in |

## Por que scaffold-eth-2 e Blockscout não são vendorizados

Mesma lógica que levou à remoção de `base/indexer` depois de extrair dele só o que interessava: este
módulo guarda apenas o que é nosso — contratos, scripts de deploy/seed/verify, compose. Os dois
projetos de terceiros são clonados à parte pelo desenvolvedor (`README.md`) — evita inflar o histórico
do Evex com código de terceiros e evita divergir de updates upstream. `.gitignore` deste módulo exclui
as duas pastas de clone (`local-env-scaffold-eth-2/`, `local-env-blockscout/`) e `.logs/` (saída dos
scripts).

## Limitações/achados técnicos conhecidos

- **Hardhat × tracer de transações internas**: Hardhat não suporta tracer customizado em
  `debug_traceBlockByNumber` — o Blockscout oficial já lida com isso usando
  `INDEXER_INTERNAL_TRANSACTIONS_TRACER_TYPE=opcode` (não "desabilitar transações internas", suposição
  inicial errada corrigida na DEC-053). Não afeta detecção automática de ERC20/721/1155 (E6), baseada
  em logs, não em trace.
- **Imagem publicada do Blockscout tem bug real** (Postgrex/`db_connection` referenciando `:ssl`
  incondicionalmente mesmo com SSL desabilitado) — sem tag estável publicada que corrija isso
  (DEC-055). Resolvido compilando do source (`v11.2.8`, `docker-compose.yml` builda sozinho) — achado
  corrigido na DEC-056 depois que o usuário questionou a conclusão inicial de "aceitar como limitação".
- **2 bugs reais achados no `BlockscoutClient.kt`** do backend (não só do ambiente de teste), só
  visíveis contra uma instância real: `filter=verified` inválido (o filtro hoje é por linguagem, não
  por status), e `address` sendo objeto aninhado (`{hash, name}`) — ambos corrigidos (DEC-057).
- **Verificação de contrato**: não dá pra usar o `rocketh-verify` do scaffold-eth-2 (fala API antiga
  estilo Etherscan) — `blockscout/verify-contracts.ts` chama a API v2 real diretamente, usando o
  `standard-json-input` que o próprio Hardhat já gera.

## Estrutura

```
local-env/
├── CLAUDE.md                          # este arquivo
├── README.md                          # guia operacional completo (subir, testar, exemplos de API)
├── docker-compose.yml                 # Postgres do Evex (sempre) + Blockscout real (profile "blockscout", opt-in)
├── .gitignore                         # exclui os 2 clones + .logs/
├── scripts/
│   ├── up.sh                          # copia contratos, deploya, dispara eventos, sobe docker (sem blockscout) + control-panel + frontend do Evex
│   ├── down.sh                        # derruba tudo que up.sh subiu (frontend + control-panel + Postgres)
│   ├── blockscout-up.sh               # sobe só o Blockscout (profile "blockscout"), sob demanda
│   └── blockscout-down.sh             # derruba só o Blockscout
├── control-panel/
│   └── server.ts                      # HTTP mínimo (127.0.0.1:8899) que a aba "Demonstração" do frontend chama pra rodar deploy/emit-events/register (E17, DEC-075)
├── contracts/
│   ├── TestERC20.sol                  # wrapper sobre OZ ERC20 com mint() público
│   ├── TestERC721.sol                 # wrapper sobre OZ ERC721 com safeMint() público
│   ├── TestERC1155.sol                # wrapper sobre OZ ERC1155 com mint()/mintBatch() públicos
│   ├── TestEventEmitter.sol           # contrato customizado, eventos não-padrão
│   ├── TestUpgradeableVaultV1.sol     # implementação V1 (UUPS/Initializable) — evento Deposited
│   ├── TestUpgradeableVaultV2.sol     # implementação V2, mesmo storage layout — + evento Withdrawn
│   ├── TestUpgradeableVaultProxy.sol  # ERC1967Proxy "puro" (subclasse só pra ganhar artifact próprio)
│   ├── TestComputer.sol               # peças trocáveis/processingPower + getConfig(id) (E19, cenário de validação oficial)
│   └── TestGameLibrary.sol            # jogos que só rodam com poder suficiente (E25, cenário do Modelo de Entidade)
├── deploy/
│   └── 01_deploy_test_contracts.ts    # script a colar em packages/hardhat/deploy/ do scaffold-eth-2
├── seed/
│   ├── register-contracts.ts          # chama POST /v1/contracts + /abi no Evex após o deploy
│   ├── emit-events.ts                 # dispara mint/safeMint/mintBatch/createWidget/etc. via ethers (substitui a UI /debug, DEC-059)
│   ├── deploy-upgradeable-vault.ts    # deploya o cofre em V1 (implementação + proxy inicializado) + deposit()
│   ├── upgrade-upgradeable-vault.ts   # upgrada o proxy pra V2 (upgradeToAndCall) + deposit()+withdraw()
│   ├── computer-vault-scenario.ts     # cenário Computador+Cofre (E19) — troca de peças em blocos espaçados + deposit/withdraw proporcional
│   ├── change-computer-power.ts       # ajuste avulso de processingPower (E19) — +delta deposita, -delta saca, sem repetir o roteiro fixo
│   ├── deploy-game-library.ts         # deploya TestGameLibrary apontando pro TestComputer já deployado (E25)
│   └── game-library-scenario.ts       # cenário de validação do Modelo de Entidade (E25) — jogos + revert real + acumulação de 3 eventos
├── blockscout/
│   ├── README.md                      # receita manual completa (build, fix de permissão, subir, testar)
│   ├── override-local-build.yml       # override de imagem/porta usado pelo docker-compose.yml da raiz
│   └── verify-contracts.ts            # verifica contratos via API v2 real (sem rocketh-verify)
└── blockscout-stub/
    └── server.ts                      # stub HTTP alternativo (mais leve, não testa o Blockscout de verdade)
```

## Regras

- Nada aqui é consumido em tempo de execução pelo `backend/` ou `frontend/` — é infraestrutura de
  apoio, rodada manualmente/via script pelo desenvolvedor quando for testar/demonstrar o Evex.
- Contratos aqui são deliberadamente simples e sem controle de acesso (`mint` público, sem `Ownable`)
  — é ambiente de teste, não reflete padrão de contrato de produção.
- `network` do lado do Evex para tudo que vier daqui é sempre `TESTNET`.
- Mesma regra de idioma do resto do repo: identificadores em inglês, comentários/KDoc/Solidity NatSpec
  em português.
- Backend do Evex nunca entra no `docker-compose.yml` deste módulo (de propósito — mantém debug/
  hot-reload pela IDE). Não rodar `backend/docker-compose.yml` junto com este arquivo — os dois
  publicam a porta 5432 do Postgres, colidem.
