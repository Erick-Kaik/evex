# Evex — EVM Event Indexer

> Rascunho de produto gerado em sessão de ideação.  
> Status: pré-PoC — não validado com gestor/produto.  
> **Antes de qualquer desenvolvimento:** ler o código base em `/home/ekaik/Projetos/PoC/Evex/base/indexer` e usar este documento como referência de produto para guiar a estruturação do projeto.

---

## 1. Problema

Toda plataforma blockchain precisa de indexação de eventos on-chain. Hoje cada projeto reimplementa isso do zero — ocorreu na Anbima, ocorreu no ABBC, vai ocorrer nos próximos projetos. O custo é alto e o resultado é sempre acoplado ao contexto específico de cada projeto.

Não existe solução equivalente para redes privadas Besu. O The Graph resolve isso para redes públicas, mas não atende esse cenário.

---

## 2. Proposta

Uma API de indexação **totalmente genérica e configurável**, que qualquer projeto ou cliente da BBChain possa instalar e usar sem precisar reimplementar a camada de indexação. O produto serve tanto internamente (entre projetos da BBChain) quanto potencialmente como serviço para clientes que operam na rede privada.

---

## 3. Stack técnica

### Backend

| Item | Decisão |
|---|---|
| Linguagem | Kotlin |
| JVM | Java 21 |
| Framework | Spring Boot 4.1.0 (Spring Framework 7 / Jakarta EE) |
| Build | Gradle — Kotlin DSL (`build.gradle.kts`) |
| Banco | PostgreSQL 16 (JSONB para ABIs) |
| Blockchain | Web3j — direto, sem BBChain Keeper |
| HTTP client | Spring `RestClient` |
| Contrato de API | OpenAPI 3.1 — openapi-generator (server stub) |
| Assíncrono | Spring `@Async` + Coroutines |
| Testes | JUnit 5 + MockK + Testcontainers |
| Testes blockchain | Hardhat local com contratos e fluxos já existentes |

### Frontend

| Item | Decisão |
|---|---|
| Framework | React 19 |
| Linguagem | TypeScript (strict) |
| Bundler | Vite (via `npm create vite@latest`) |
| HTTP client | Gerado pelo openapi-generator (typescript-axios) |
| State / fetching | TanStack Query v5 |
| Estilo | ~~Tailwind CSS v4~~ MUI (Material-UI) — revisado na DEC-060 (E13), ver nota em §9 |
| Roteamento | React Router v7 |

---

## 4. Premissas técnicas

- Rede: **Hyperledger Besu** (EVM-compatível)
- Explorer: **Blockscout** integrado à rede (utilizado para discovery de ABIs)
- O processo de deploy da rede exige `.bin` + `.abi` — o que provavelmente significa que contratos são verificados automaticamente no Blockscout no momento do deploy
- A API é instalada **por rede** (uma instância por cliente/ambiente), eliminando a necessidade de multi-tenancy complexo na PoC

---

## 5. Funcionalidades

### 4.1 Gerenciamento de contratos

O usuário configura quais contratos deseja monitorar:

- Endereço do contrato
- Nome e descrição
- Versão (mesmo contrato, endereços diferentes por versão — histórico preservado por endereço)
- Rede: `MAINNET` ou `TESTNET`
- Status: ativo / pausado / arquivado

**Formas de adicionar ABI:**
- Upload do arquivo `.abi` diretamente
- Colagem do conteúdo JSON da ABI como texto (útil quando o usuário tem o ABI mas não o arquivo físico, ou quer copiar de outra fonte)
- Discovery via Blockscout: escaneia a rede, lista contratos verificados, usuário seleciona quais monitorar
- Detecção automática de interfaces padrão (ver seção 4.5)

O endpoint de cadastro de ABI aceita um campo `storage` opcional que define onde o conteúdo será persistido. Se omitido, usa o default configurado na instância via `application.yml`:

```
POST /contracts/{address}/abi
{
  "abi": "[{...}]",
  "storage": "database"   ← "database" | "local" | "hybrid" (opcional)
}
```

Ver seção 4.8 para detalhes do modelo de storage.

### 4.2 Pipeline de indexação

```
Rede Besu (polling ou WebSocket)
        ↓
Listener de novos blocos
        ↓
Parser de logs por contrato monitorado
        ↓
Classificação
    ├── ABI conhecida → evento decodificado → salvo estruturado
    └── ABI desconhecida → evento salvo raw (topic0 + data bruta)
        ↓
Storage (PostgreSQL)
    ├── Eventos decodificados
    ├── Eventos raw (pendentes de mapeamento)
    ├── Saldos de ativos
    └── ABIs cadastradas
```

**Ponto importante:** nenhum evento é descartado. Eventos que chegam antes do cadastro da ABI são salvos como raw e reprocessados depois — sem perda de histórico.

### 4.3 Reindexação assíncrona

Quando o usuário adiciona uma ABI para um contrato que já tem histórico de eventos raw:

- Dispara reprocessamento assíncrono em pequenos lotes (já validado na Anbima)
- Busca eventos raw pelo `contract_address`
- Decodifica usando a ABI recém-cadastrada
- Migra para a estrutura de eventos decodificados

Granularidades disponíveis para reindexação:
- Por `contract_address` ← caso principal
- Por `topic0` ← útil quando a mesma ABI se aplica a múltiplos contratos
- Por janela de blocos ← útil para reprocessar um período específico

### 4.4 Saldos históricos

Tabela dedicada e configurável, independente do fluxo de eventos gerais.

**Modelo de dados:**

```
asset_balances (saldo atual)
├── wallet_address
├── contract_address
├── token_id          — null para ERC20, preenchido para ERC1155
├── network
├── balance
└── updated_at

asset_balance_snapshots (histórico diário)
├── wallet_address
├── contract_address
├── token_id
├── network
├── balance
├── snapshot_date
└── block_number      — até qual bloco foi calculado
```

Job noturno consolida o saldo atual em snapshot diário. Consulta histórica ("qual era o saldo desta carteira neste token em 2025-07-10?") vira uma query simples por `snapshot_date`.

**Configuração de monitoramento de saldo:**

O usuário define quais pares `token + carteira` monitorar e como o sistema deve interpretar os eventos para cálculo de saldo:

```
├── contract_address   — qual contrato/token
├── event_name         — qual evento usar para cálculo
├── wallet_param       — parâmetro do evento que contém o endereço
│                         (ou from_param + to_param para TRANSFER)
├── value_param        — parâmetro do evento que contém o valor
├── behavior           — como interpretar o evento (ver abaixo)
└── ativo / inativo
```

**Tipos de comportamento (behavior):**

| Behavior | Quando usar | Exemplo |
|---|---|---|
| `CREDIT` | Evento sempre soma ao saldo | `TokenMinted(recipient, amount)` |
| `DEBIT` | Evento sempre subtrai do saldo | `TokenBurned(holder, amount)` |
| `TRANSFER` | Deduz de um endereço e credita em outro | `Transfer(from, to, value)` ERC20 |
| `DELTA` | O parâmetro já é o delta (positivo ou negativo) | `BalanceUpdated(wallet, int256 delta)` |

### 4.5 Detecção automática de interfaces padrão

O sistema tem templates built-in para interfaces ERC conhecidas. O topic0 de cada evento é derivado da assinatura — universal, independente do contrato que o implementa.

**Templates built-in:**

| Interface | Eventos |
|---|---|
| ERC20 | `Transfer`, `Approval` |
| ERC721 | `Transfer`, `Approval`, `ApprovalForAll` |
| ERC1155 | `TransferSingle`, `TransferBatch`, `ApprovalForAll` |

Ao cadastrar um contrato, o sistema escaneia os primeiros logs e compara os topic0 encontrados com os templates conhecidos. Se houver match, sugere o template automaticamente — sem necessidade de upload de ABI ou configuração manual de saldo.

**Níveis de configuração resultantes:**

```
Nível 1 — Automático
  Interface padrão detectada → template built-in → zero configuração

Nível 2 — Semi-automático
  Contrato misto (padrão + eventos próprios)
  → template para a parte padrão
  → configuração manual para os eventos proprietários

Nível 3 — Manual
  Contrato 100% proprietário
  → ABI obrigatória (upload ou Blockscout)
  → behavior + mapeamento de campos totalmente manual
```

### 4.6 Webhooks

Permite que sistemas externos sejam notificados automaticamente quando um evento de interesse ocorre.

- Configurável por usuário, contrato e tipo de evento
- Filtros opcionais (ex: só disparar se `value > X`)
- Retry automático com backoff em caso de falha
- Log de tentativas e status de entrega
- Payload customizável por webhook

### 4.7 Segregação Mainnet / Testnet

Campo `network` com valor `MAINNET` ou `TESTNET` em todas as entidades. A instância da API é configurada com as URLs dos nós de cada rede. O cliente pode optar por instâncias separadas por ambiente ou usar a segregação por campo na mesma instância.

---

## 6. Interface mínima (UI da PoC)

Suficiente para demonstrar o valor sem precisar de imaginação:

- Listagem e cadastro de contratos monitorados
- Visualização de eventos indexados com filtros básicos
- Tela de saldos históricos (por carteira, token e data)
- Configuração de webhooks

---

## 7. Integração com Blockscout

```
Blockscout (já integrado à rede privada)
├── GET /api/v2/smart-contracts?filter=verified
│     → lista todos os contratos verificados para discovery
│
├── GET /api/v2/smart-contracts/{address}
│     → ABI completa se contrato verificado
│
└── GET /api/v2/addresses/{address}/logs
      → logs históricos para reindexação inicial e detecção de interfaces
```

---

## 8. Arquitetura em camadas

```
┌──────────────────────────────────────────────────────────┐
│                       API REST                           │
│     (configuração de contratos, consulta, webhooks)      │
├──────────────────────────────────────────────────────────┤
│                  Motor de indexação                      │
│         (listener → parser → classificador)              │
├────────────────────────┬─────────────────────────────────┤
│  PostgreSQL            │  Cache em memória               │
│  ├── eventos           │  └── Map<address, ABI>          │
│  ├── raws              │       (populado no startup,     │
│  ├── saldos            │        atualizado on-change)    │
│  └── contract_abis     │                                 │
│      (índice central)  │                                 │
├────────────────────────┼─────────────────────────────────┤
│  ABI Storage Port      │  Webhook dispatcher             │
│  ├── DatabaseStorage   │  ├── fila de envios             │
│  ├── LocalFileStorage  │  ├── retry com backoff          │
│  └── AzureBlobStorage  │  └── log de entregas            │
│  (plugável via config) │                                 │
├────────────────────────┴─────────────────────────────────┤
│                  Blockscout client                       │
│          (discovery de contratos e ABIs)                 │
└──────────────────────────────────────────────────────────┘
```

---

## 9. Regras de desenvolvimento

### Gerais
- **Idioma do código:** inglês (variáveis, funções, classes, comentários inline)
- **Idioma da documentação:** português
- **Sem auth na PoC:** nenhuma camada de autenticação ou autorização neste momento
- **OpenAPI first:** o contrato `.yaml` é a fonte de verdade — nunca escrever controllers ou clientes HTTP manualmente, sempre gerar via openapi-generator
- **Instância por rede:** uma instância do Evex por cliente/ambiente; segregação interna por campo `network` (`MAINNET` | `TESTNET`)

### Backend
- **Arquitetura hexagonal:** domain sem dependências de framework; adapters de entrada (API) e saída (persistence, blockchain, storage, webhook) separados
- **Sem web3j-gradle-plugin:** o plugin gera código em compile-time e é incompatível com ABIs dinâmicas; usar decodificação dinâmica em runtime via API de baixo nível do Web3j
- **Sem BBChain Keeper:** comunicação com Web3j direta, sem passar pelo BBChain Keeper
- **Banco como índice central de ABIs:** PostgreSQL é sempre o índice, independente do storage escolhido; o storage externo (local/blob) é artefato complementar
- **Cache em memória de ABIs:** populado no startup, atualizado a cada mudança; o caminho crítico de decodificação nunca bate em banco ou disco
- **Spring Boot 4.x / Jakarta EE:** imports `jakarta.*` (não `javax.*`)
- **Kotlin idiomático:** data classes, extension functions, sealed classes para resultados; sem `Any` ou `nullable` desnecessário
- **Reindexação assíncrona em lotes:** nunca síncrona, nunca em bloco único

### Frontend
- **React Query para fetching:** sem `useEffect` para chamadas de API
- **Cliente gerado:** nunca escrever chamadas HTTP manualmente; usar cliente gerado pelo openapi-generator a partir do mesmo `.yaml` do backend
- **TypeScript strict:** sem `any` explícito
- ~~**Tailwind:** sem CSS modules ou styled-components~~ — revisado conscientemente na DEC-060 (`docs/decisions.md`): projeto migrou pra MUI (E13). Registro original mantido riscado como histórico, não é mais a regra seguida (mesmo princípio da DEC-052 sobre a DEC-010).

### Testes
- O ambiente de testes blockchain é um **Hardhat local** com contratos e fluxos de eventos já existentes em `/home/ekaik/Projetos/PoC/Evex/base/`
- Testes unitários com JUnit 5 + MockK
- Testes de integração com Testcontainers (PostgreSQL)

### Código base de referência
O projeto em `/home/ekaik/Projetos/PoC/Evex/base/indexer` é a implementação semi-genérica feita para o projeto Anbima. Deve ser lido antes de qualquer desenvolvimento para identificar o que pode ser reaproveitado diretamente, o que precisa de adaptação e o que precisa ser reescrito para a versão genérica.

---

## 10. Escopo da PoC vs Pós-aprovação

### PoC
- [ ] Indexação genérica (adaptar base da Anbima)
- [ ] Segregação MAINNET / TESTNET
- [ ] Configuração de contratos via API REST
- [ ] Cadastro de ABI via arquivo, texto colado e Blockscout
- [ ] Storage de ABI plugável: `database` e `local` (padrão da PoC)
- [ ] Modo híbrido com filesystem local (banco como índice + arquivo local)
- [ ] Cache em memória de ABIs (populado no startup, atualizado on-change)
- [ ] Decodificação dinâmica de ABI em runtime (sem código gerado)
- [ ] Discovery e importação de ABI via Blockscout
- [ ] Detecção automática de interfaces ERC20 / ERC721 / ERC1155
- [ ] Eventos raw com reindexação assíncrona por lotes
- [ ] Saldos históricos configuráveis com snapshots diários
- [ ] Webhooks configuráveis com retry e log
- [ ] UI mínima (cadastro, eventos, saldos, webhooks)

### Pós-aprovação
- [ ] Multi-tenancy (isolamento por cliente/projeto)
- [ ] Suporte a múltiplas redes diferentes (além de mainnet/testnet)
- [ ] Storage via Azure Blob (produção / alinhado com EBS)
- [ ] UI completa
- [ ] Integração com ecossistema Ptah (análise detalhada necessária antes)

---

## 11. Integração com o Ecossistema Ptah (análise de viabilidade)

> Esta seção é exploratória — não é escopo da PoC, mas uma direção de evolução a validar.

### O que é o Ptah

O BBChain Ptah é um framework modular e distribuído para redes financeiras, com três pilares centrais: **privacidade por design** (compartimentalização criptográfica), **composabilidade** (componentes independentes com interfaces padronizadas) e **interoperabilidade** (integração entre redes e plataformas distintas). Foi testado na fase 2 do Drex em cenários como CCBt (ABBC), emissão de títulos privados e trade finance cross-border, além de ter sido selecionado para o Lift Lab do BACEN/FENASBAC e publicado no IEEE.

Sua arquitetura opera com componentes como Balance Nodes, orquestradores e validadores de assinatura — cada um com acesso restrito apenas aos dados relevantes à sua função.

### O problema central: compartimentalização vs. indexação

O indexer na sua forma atual assume que **eventos on-chain são visíveis** a quem está escutando a rede. O Ptah inverte essa premissa: sua compartimentalização criptográfica garante que cada componente veja apenas o que precisa. Isso cria uma tensão direta:

```
Indexer tradicional          Ptah
─────────────────────        ──────────────────────────────
Lê todos os eventos  ←→     Eventos privados só visíveis
da rede livremente           para participantes autorizados
```

### O que seria indexável no contexto Ptah

Há uma separação importante entre o que o Ptah registra on-chain vs. o que mantém em camadas privadas:

**Provavelmente indexável sem adaptação:**
- Eventos de finalização de transação atômica (o commit da Two-Phase Commit que vai on-chain como prova de execução)
- Eventos públicos emitidos pelo orquestrador como resultado de fluxos concluídos
- Saldos finais registrados on-chain após liquidação

**Provavelmente não indexável sem integração específica:**
- Eventos internos das fases intermediárias do 2PC (são off-chain ou em grupos privados)
- Dados de validadores e orquestradores que operam sobre metadados compartimentalizados
- Eventos cross-network de cenários de interoperabilidade que ocorrem em redes externas

### O que mudaria na arquitetura do indexer para suportar Ptah

**Multi-tenancy se torna obrigatório, não opcional**

No contexto Ptah, diferentes participantes de um consórcio têm visibilidades diferentes dos mesmos eventos. O indexer precisaria respeitar esse modelo — um banco A não pode ver eventos que só o banco B deveria ver, mesmo que tecnicamente ambos estejam na mesma rede.

**Integração com a camada de orquestração do Ptah**

Em vez de (ou além de) escutar a rede diretamente, o indexer poderia receber notificações da camada de orquestração do Ptah — que já sabe quais eventos cada participante pode ver. Isso transforma o fluxo de:

```
Antes: Rede → Indexer (vê tudo) → filtra por contrato
Depois: Ptah Orchestrator → Indexer (recebe só o que pode) → salva
```

**Suporte a múltiplas redes se torna necessário**

O Ptah opera em cenários cross-network (ex: CBDC + TPT em redes distintas). Para indexar fluxos completos nesses cenários, o indexer precisaria correlacionar eventos de redes diferentes que fazem parte do mesmo fluxo atômico.

**Tratamento de estados intermediários**

O Two-Phase Commit significa que um evento pode estar em estado "preparado" antes de ser "commitado". O indexer precisaria tratar esses estados — algo inexistente no modelo atual onde só eventos finalizados são indexados.

### Conclusão de viabilidade

| Cenário | Viabilidade |
|---|---|
| Indexar eventos públicos resultantes de fluxos Ptah | ✅ Viável sem adaptação |
| Indexar como camada de observabilidade sobre o Ptah | ✅ Viável com adaptação moderada |
| Indexar eventos privados compartimentalizados | ⚠️ Requer integração profunda com o Ptah |
| Indexar fluxos cross-network completos | ⚠️ Requer suporte a múltiplas redes |

O caminho mais realista de evolução seria posicionar o indexer como uma **camada de observabilidade** do ecossistema Ptah — consumindo eventos que o Ptah deliberadamente expõe para auditoria e rastreabilidade, respeitando o modelo de privacidade. Isso seria um diferencial significativo: o único indexer que entende o modelo de privacidade do Ptah.

Antes de aprofundar tecnicamente, a pergunta a responder internamente é: **quais eventos o Ptah expõe publicamente em cada fluxo?** Essa resposta define o esforço real de integração.

---

## 12. Argumento para apresentação interna

**Custo atual:** cada projeto reimplementa indexação do zero — tempo de desenvolvimento, bugs repetidos, acoplamento ao contexto específico.

**Com o produto:** um serviço instalável, qualquer projeto interno configura em minutos. Para contratos ERC20/ERC721/ERC1155 o usuário literalmente só informa o endereço — o sistema detecta a interface e configura automaticamente.

**Validação existente:** a lógica central já foi implementada e testada em produção no projeto Anbima. O produto é a generalização de algo que já funciona.

**Potencial externo:** clientes da BBChain que operam na rede privada podem usar o mesmo serviço para integrar seus próprios sistemas via webhooks, sem precisar entender a camada blockchain.

### 4.8 Storage de ABIs

#### Princípios

- **PostgreSQL é sempre o índice** — qualquer operação de busca, auditoria ou listagem passa pelo banco. Isso garante velocidade e consistência independente do storage escolhido.
- **Cache em memória** — populado a partir do banco no startup e atualizado a cada mudança. O caminho crítico de decodificação nunca toca banco ou disco diretamente.
- **Storage como porta (Hexagonal)** — a implementação de storage é plugável via configuração. O indexer não sabe qual está usando, só chama a interface.

#### Implementações disponíveis

```
AbiStoragePort (interface)
    ├── DatabaseAbiStorage   → PostgreSQL JSONB         (padrão, PoC)
    ├── LocalFileAbiStorage  → filesystem local          (dev / PoC)
    └── AzureBlobAbiStorage  → Azure Blob Storage        (produção / EBS)
```

Troca via `application.yml` sem mudança de código:

```yaml
indexer:
  abi-storage: database   # database | local | azure-blob
```

#### Modelo de dados no banco (sempre presente)

```
contract_abis
├── id
├── contract_address
├── network
├── version
├── name
├── source          — UPLOAD_FILE | UPLOAD_TEXT | BLOCKSCOUT | AUTO_DETECTED
├── storage_type    — DATABASE | LOCAL | HYBRID
├── abi_content     — JSONB (preenchido para DATABASE e HYBRID)
├── storage_path    — texto (preenchido para LOCAL e HYBRID)
└── created_at
```

O campo `abi_content` e `storage_path` se complementam conforme o tipo:

| storage_type | abi_content | storage_path |
|---|---|---|
| `DATABASE` | ✅ preenchido | ❌ nulo |
| `LOCAL` | ❌ nulo | ✅ preenchido |
| `HYBRID` | ✅ preenchido | ✅ preenchido |

#### Modo híbrido na PoC

No lugar do Azure Blob, o modo híbrido usa o filesystem local. O comportamento é idêntico ao híbrido de produção — banco como índice + metadados, arquivo como artefato — com a diferença sendo apenas o destino do arquivo. Isso permite demonstrar o comportamento híbrido completo sem infraestrutura de cloud.

```
Híbrido (produção)  →  metadados + JSONB no DB  +  arquivo no Azure Blob
Híbrido (PoC)       →  metadados + JSONB no DB  +  arquivo no filesystem local
```

#### Fluxo do cache em memória

```
Startup
└── carrega todos os ABIs ativos do banco → Map<contractAddress, ABI> em memória

Evento chega para decodificação
└── busca no cache em memória → decodifica (sem I/O)

Cache miss
└── banco → descobre storage_type → busca conteúdo → popula cache

Novo ABI cadastrado ou atualizado
└── salva no banco (e no storage externo se aplicável) → invalida cache → recarrega
```

#### Resiliência

Se o storage externo (local ou blob) ficar indisponível, o sistema continua operando enquanto o ABI estiver no cache. O storage externo no modo híbrido é artefato complementar, não dependência crítica.

### 4.9 Mudança de paradigma: código gerado vs. decodificação dinâmica

A versão atual do indexer (Anbima) usa o **web3j-gradle-plugin** para gerar classes Kotlin tipadas a partir dos ABIs em tempo de compilação. Isso não é compatível com a versão genérica — o usuário adiciona contratos em runtime, e não é viável rebuildar o serviço a cada novo contrato.

A versão genérica substitui o código gerado por **decodificação dinâmica de ABI em runtime**, suportada nativamente pelo Web3j:

```
Versão atual (Anbima)              Versão genérica
──────────────────────────         ──────────────────────────────
Build time: plugin lê .abi    →    Nenhum código gerado
            gera classes           
Runtime: usa classe gerada    →    Carrega ABI do banco/cache
         MeuContrato.load()        Monta Event dinamicamente
                                   Decodifica log em runtime
```

O resultado é funcionalmente equivalente — a diferença é que o ABI vem do banco em vez de uma classe gerada. O Gradle plugin é removido da versão genérica.
