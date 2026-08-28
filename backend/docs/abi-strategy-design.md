# Fluxo de ABI — design com Strategy Pattern

> O código base (`base/indexer`) resolve ABI como um catálogo único, estático, carregado do classpath no boot (`AbiEventCatalog`) — não tem os eixos de variação que o Evex precisa (storage plugável, versão por bloco, detecção automática de interface, múltiplas origens de cadastro). Este documento propõe o design do Evex usando **Strategy Pattern** para cada eixo de variação, mantendo a decodificação dinâmica de ABI (Web3j) como núcleo comum reaproveitado.

## Por que Strategy (e não `if/else`/`when` espalhado)

O doc de produto já descreve 4 pontos de variação plugável por configuração:
1. **Onde a ABI é armazenada** (`storage`: `database` | `local` | `azure-blob`).
2. **Como o saldo é calculado a partir de um evento** (`behavior`: `CREDIT` | `DEBIT` | `TRANSFER` | `DELTA`).
3. **Como uma interface padrão é reconhecida** (templates ERC20/ERC721/ERC1155).
4. **Como a ABI chegou ao sistema** (`source`: `UPLOAD_FILE` | `UPLOAD_TEXT` | `BLOCKSCOUT` | `AUTO_DETECTED`).

Cada um é um caso clássico de Strategy: mesmo contrato (interface), comportamento escolhido em runtime por um valor de configuração/enum, sem `when` gigante espalhado pelos services. Um `Resolver`/`Factory` (Spring: `Map<String, Port>` injetado por nome de bean, ou `@ConditionalOnProperty`) escolhe a estratégia concreta.

---

## 1. `AbiStoragePort` — Strategy de armazenamento

```
interface AbiStoragePort {
    fun store(abi: AbiContent): StorageLocation
    fun retrieve(location: StorageLocation): AbiContent
    fun delete(location: StorageLocation)
}

DatabaseAbiStorage  : AbiStoragePort   # grava abi_content (JSONB) na própria linha de contract_abis
LocalFileAbiStorage : AbiStoragePort   # grava no filesystem, contract_abis.storage_path aponta pro arquivo
AzureBlobAbiStorage : AbiStoragePort   # pós-aprovação — mesma interface, destino é Azure Blob
```

- **PostgreSQL é sempre o índice** (regra não-negociável do doc): mesmo em `LOCAL`/`HYBRID`, a linha em `contract_abis` existe e tem os metadados; só o *conteúdo* pode estar fora do banco.
- Seleção da estratégia: por config global (`indexer.abi-storage: database|local|azure-blob`) **e** por override opcional no `POST /contracts/{address}/abi` (campo `storage` da seção 4.1 do doc). Um `AbiStorageResolver` decide: usa o valor do request se presente, senão o default do `application.yml`.
- Modo `HYBRID`: não é uma 4ª estratégia — é a composição de `DatabaseAbiStorage` (metadados+JSONB) **+** `LocalFileAbiStorage`/`AzureBlobAbiStorage` (artefato). Vale modelar como um `HybridAbiStorage` que delega para as duas, ou como um passo explícito no service de cadastro que grava nos dois lugares conforme `storage_type`.
- **Resiliência**: se o storage externo cair, o sistema continua servindo do cache em memória (ver seção 4 abaixo) — o storage externo nunca é dependência crítica do caminho de decodificação.

## 2. `BalanceBehaviorStrategy` — Strategy de cálculo de saldo

Hoje (`BalanceProjectionService` no código base) o cálculo é hard-coded para `Transfer`/`TransferSingle`/`TransferBatch` com campos fixos `from`/`to`/`value`/`id`. O Evex precisa generalizar para os 4 behaviors do doc (§4.4):

```
interface BalanceBehaviorStrategy {
    fun apply(config: BalanceMonitorConfig, decoded: DecodedEvent, block: Long, logIndex: Long, ts: Instant): List<BalanceDelta>
}

CreditBehavior   : sempre soma ao saldo do wallet_param        # TokenMinted(recipient, amount)
DebitBehavior    : sempre subtrai do saldo do wallet_param     # TokenBurned(holder, amount)
TransferBehavior : deduz de from_param, credita em to_param    # Transfer(from, to, value) — mesma lógica do
                                                                  código base, mas from/to vêm de config, não hard-coded
DeltaBehavior    : o value_param já é o delta (signed)         # BalanceUpdated(wallet, int256 delta)
```

- `BalanceMonitorConfig` é a entidade do doc (`contract_address`, `event_name`, `wallet_param`, `value_param`, `behavior`, `ativo/inativo` — mais `from_param`/`to_param` quando `behavior = TRANSFER`).
- O `EventIngestionService`/`RedecodeBatchProcessor` não decide mais "é Transfer, aplica delta" — pergunta `balanceMonitorRepository.findActiveFor(contractAddress, eventName)` e, se existir, resolve a `BalanceBehaviorStrategy` pelo enum `behavior` e delega.
- **Reaproveitar do código base**: a mecânica de acumular deltas em um "batch" em memória antes do `saveAll` (padrão `BalanceProjectionBatch` — cache por chave `(contract, tokenId, holder)` dentro da transação, para múltiplos eventos do mesmo lote tocando o mesmo holder acumularem no mesmo objeto) continua válida e é ortogonal ao behavior escolhido.
- Endereço zero como sentinela de mint/burn (`0x000...0`) só faz sentido no `TransferBehavior` — `CreditBehavior`/`DebitBehavior` não têm essa noção (o evento já é unidirecional por natureza).

## 3. `InterfaceTemplate` — Strategy de detecção automática (doc §4.5)

```
interface InterfaceTemplate {
    val interfaceName: String              # "ERC20", "ERC721", "ERC1155"
    val eventTopic0s: Set<String>          # topic0 de cada evento da interface (calculado 1x, é universal)
    val builtinAbi: AbiContent             # ABI mínima built-in para decodificar sem cadastro manual
}

Erc20Template   : Transfer, Approval
Erc721Template  : Transfer, Approval, ApprovalForAll
Erc1155Template : TransferSingle, TransferBatch, ApprovalForAll
```

```
InterfaceDetector(templates: List<InterfaceTemplate>):
    detect(observedTopic0s: Set<String>): List<InterfaceMatch>
        para cada template, calcula quantos dos seus eventTopic0s aparecem em observedTopic0s
        match total   -> Nível 1 (automático, template inteiro cobre o contrato)
        match parcial -> Nível 2 (semi-automático: parte padrão + eventos próprios sobrando sem match)
        sem match     -> Nível 3 (manual, ABI obrigatória)
```

- `topic0` de cada evento padrão é **universal** (é `keccak256` da assinatura, independe de quem implementa) — os `EventDescriptor` desses templates podem ser pré-computados uma vez no boot (mesmo mecanismo de `EventEncoder.encode` já usado no `AbiEventCatalog` do código base) e ficam fixos no código, não vêm de cadastro do usuário.
- Fluxo de cadastro de contrato: escaneia os primeiros logs do endereço (via Blockscout `GET /addresses/{address}/logs` ou via `eth_getLogs` pontual) → extrai `topic0`s distintos → `InterfaceDetector.detect(...)` → sugere o(s) template(s) → usuário confirma.

## 4. `AbiIngestionStrategy` — Strategy de origem do cadastro (doc §4.1, campo `source`)

```
interface AbiIngestionStrategy {
    fun resolve(request: RegisterAbiRequest): AbiContent
}

UploadFileStrategy     : lê o multipart, valida JSON de ABI
UploadTextStrategy     : valida o JSON colado como texto
BlockscoutStrategy     : GET /smart-contracts/{address} no client Blockscout, extrai a ABI
AutoDetectedStrategy   : usa o builtinAbi do InterfaceTemplate detectado (item 3)
```

Todas produzem o mesmo `AbiContent` (JSON de ABI válido) — o resto do fluxo de cadastro (persistir metadados em `contract_abis`, escolher `AbiStoragePort`, invalidar cache, disparar reindexação) é comum e não depende de qual estratégia originou a ABI.

## 5. Versionamento de ABI por faixa de blocos (proxy upgradável — decisão desta análise, fora do doc original)

Contratos com proxy (ex.: `ERC1967Proxy`, presente nos ABIs de referência da Anbima) podem trocar de implementação **no mesmo endereço**. Isso significa que `contract_abis` não é 1 linha por endereço — é N linhas por endereço, cada uma com uma faixa de validade:

```
contract_abis
├── contract_address
├── valid_from_block     -- inclusive
├── valid_to_block        -- nullable = ainda vigente
└── (demais campos do doc §4.8: version, source, storage_type, abi_content, storage_path)
```

Resolução no path de decodificação: `findAbiValidAt(contractAddress, blockNumber)` — não é mais um Strategy (não há comportamento alternativo, é uma query), mas **afeta diretamente o cache em memória**: não pode ser `Map<contractAddress, Abi>` simples (só a ABI mais recente); precisa resolver "qual ABI valia neste bloco" — seja mantendo uma lista ordenada por contrato em memória, seja com um cache mais grosso (por `contract_address`) que carrega todas as faixas de uma vez.

---

## Reaproveitado do código base (núcleo comum, independente das strategies acima)

O parsing do JSON `.abi` e a decodificação em si (`AbiEventCatalog`/`EventDecoder`/`EventDescriptor` do código base) **não muda** com este redesenho — continua sendo: `TypeReference.makeTypeReference(type, indexed, false)` por input → `Event(name, typeRefs)` → `EventEncoder.encode(event)` para o `topic0` → `Contract.staticExtractEventParameters(event, log)` para decodificar → formatação de `BigInteger`/`ByteArray`/`List` para o payload nomeado. O que muda é só **de onde vem o `Event`** (hoje: classpath estático; Evex: resolvido por `contract_address` + bloco, através do cache descrito no item 5).
