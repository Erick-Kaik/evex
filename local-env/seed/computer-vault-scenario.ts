/**
 * Roteiro do "cenário de validação oficial" da E19 (eventos enriquecidos, ver `docs/roadmap.md`
 * seção E19 → "Cenário de validação oficial") — prova o ponto mais crítico da ideia: o `eth_call`
 * de um Perfil de Enriquecimento precisa ler o estado **no bloco em que o evento disparador
 * ocorreu**, não no bloco atual/tip da chain.
 *
 * Dois contratos, um evento em cada um decidindo o outro:
 *   - `TestComputer` (novo, E19) — "o bem": peças trocáveis (`motherboard`/`gpu`/`cpu`) e um
 *     `processingPower` que varia a cada `changeParts()`.
 *   - `TestUpgradeableVault` (já existente, E20) — "a moeda": a cada troca de peça, deposita
 *     (`powerDelta > 0`) ou saca (`powerDelta < 0`) uma quantidade proporcional no cofre, em nome
 *     do mesmo dono do computador (aqui, sempre a conta deployer — mesmo modelo de conta única já
 *     usado por `emit-events.ts`/`deploy-upgradeable-vault.ts`).
 *
 * Dispara: `create()` (computador) → `deposit()` (poder inicial) → 3 rodadas de
 * `changeParts()` + `deposit()`/`withdraw()`, em blocos **bem espaçados** (`hardhat_mine` avança
 * blocos vazios entre cada rodada, sem gastar gas à toa) — pra que a config do computador no bloco
 * intermediário seja **visivelmente diferente** da config final, tornando impossível confundir
 * "leu o bloco certo" com coincidência (diferente de testar só com um saldo que só cresce).
 *
 * Pré-requisitos (nesta ordem):
 *   1. `yarn deploy --tags TestContracts` (ou a ação "Deploy dos contratos de teste" do
 *      control-panel) — precisa incluir `TestComputer` (adicionado ao script de deploy na E19).
 *   2. `seed/deploy-upgradeable-vault.ts` — deploya o cofre em V1.
 *   3. `seed/upgrade-upgradeable-vault.ts` — upgrada pra V2, que é quem tem `withdraw()` (este
 *      script recusa rodar se só achar a ABI de V1 — a 3ª rodada abaixo saca, precisa de V2).
 *
 * Depois de rodar, o script imprime os blocos de cada evento do Cofre e a config esperada do
 * Computador em cada um — e os próximos passos manuais no Evex (registrar o `TestComputer`,
 * criar o Perfil de Enriquecimento, criar/editar o webhook). Grava um resumo em
 * `.logs/computer-vault-scenario.json`.
 *
 * Uso: `npx tsx local-env/seed/computer-vault-scenario.ts` (de dentro de `local-env/`).
 *
 * Variáveis de ambiente:
 *   RPC_URL               default "http://127.0.0.1:8545"
 *   DEPLOYER_PRIVATE_KEY  default conta 0 do Hardhat
 *   SCAFFOLD_ETH_DIR      default "./local-env-scaffold-eth-2" (relativo a este script)
 *   CHAIN_ID              default "31337"
 *   COMPUTER_ID           default "1" — id do computador criado por este script
 */
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

const RPC_URL = process.env.RPC_URL ?? "http://127.0.0.1:8545";
const DEPLOYER_PRIVATE_KEY =
  process.env.DEPLOYER_PRIVATE_KEY ?? "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");
const CHAIN_ID = process.env.CHAIN_ID ?? "31337";
const COMPUTER_ID = BigInt(process.env.COMPUTER_ID ?? "1");

const LOCAL_ENV_DIR = resolve(import.meta.dirname, "..");
const LOG_DIR = resolve(LOCAL_ENV_DIR, ".logs");
const VAULT_STATE_FILE = resolve(LOG_DIR, "upgradeable-vault.json");
const VAULT_V2_ABI_FILE = resolve(LOG_DIR, "vault-v2-abi.json");
const SCENARIO_STATE_FILE = resolve(LOG_DIR, "computer-vault-scenario.json");

const hardhatRequire = createRequire(pathToFileURL(resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/package.json")).href);
const { Contract, JsonRpcProvider, NonceManager, Wallet } = hardhatRequire("ethers") as typeof import("ethers");

type DeployedContract = { address: string; abi: unknown[] };
type DeployedContracts = Record<string, Record<string, DeployedContract>>;

async function loadDeployedContracts(): Promise<DeployedContracts> {
  const deployedContractsPath = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(deployedContractsPath).href);
  return module.default as DeployedContracts;
}

function resolveVaultProxyAddress(): string {
  if (process.env.VAULT_PROXY_ADDRESS) return process.env.VAULT_PROXY_ADDRESS;
  if (!existsSync(VAULT_STATE_FILE)) {
    throw new Error(
      `Nenhum cofre conhecido (${VAULT_STATE_FILE} não existe). Rode deploy-upgradeable-vault.ts antes.`,
    );
  }
  return (JSON.parse(readFileSync(VAULT_STATE_FILE, "utf8")) as { proxyAddress: string }).proxyAddress;
}

function loadVaultV2Abi(): unknown[] {
  if (!existsSync(VAULT_V2_ABI_FILE)) {
    throw new Error(
      `Cofre ainda não upgradado pra V2 (${VAULT_V2_ABI_FILE} não existe) — este cenário saca ` +
        `(withdraw) na 3ª rodada, só disponível a partir de V2. Rode upgrade-upgradeable-vault.ts antes.`,
    );
  }
  return JSON.parse(readFileSync(VAULT_V2_ABI_FILE, "utf8")) as unknown[];
}

/** Avança blocos vazios (sem transação/gas) até faltar exatamente 1 bloco pro `targetBlock` — a
 * próxima transação enviada depois desta chamada cai exatamente em `targetBlock`. Não faz nada se
 * a chain já alcançou ou passou do alvo (rodadas anteriores podem ter consumido mais blocos do que
 * o previsto). */
async function mineEmptyBlocksUntilJustBefore(
  provider: InstanceType<typeof JsonRpcProvider>,
  targetBlock: number,
): Promise<void> {
  const current = await provider.getBlockNumber();
  const count = targetBlock - current - 1;
  if (count <= 0) return;
  await provider.send("hardhat_mine", [`0x${count.toString(16)}`]);
}

interface ConfigSnapshot {
  motherboard: string;
  gpu: string;
  cpu: string;
  power: string;
}

async function main(): Promise<void> {
  const provider = new JsonRpcProvider(RPC_URL);
  const signer = new NonceManager(new Wallet(DEPLOYER_PRIVATE_KEY, provider));
  const ownerAddress = await signer.getAddress();

  const deployedContracts = await loadDeployedContracts();
  const computerDeployed = deployedContracts[CHAIN_ID]?.TestComputer;
  if (!computerDeployed) {
    throw new Error(
      `TestComputer não encontrado em deployedContracts.ts pro chainId ${CHAIN_ID}. Rode ` +
        `"yarn deploy --tags TestContracts" (com o TestComputer.sol já copiado) antes deste script.`,
    );
  }
  const vaultProxyAddress = resolveVaultProxyAddress();
  const vaultV2Abi = loadVaultV2Abi();

  const computer = new Contract(computerDeployed.address, computerDeployed.abi, signer);
  const vault = new Contract(vaultProxyAddress, vaultV2Abi, signer);

  console.log(`==> Computador: ${computerDeployed.address} | Cofre (proxy): ${vaultProxyAddress}`);
  console.log(`==> Dono (única conta usada neste cenário): ${ownerAddress}`);

  // --- Criação (bloco N) + depósito do poder inicial (bloco N+1) ---------------------------------
  console.log(`\n==> create(id=${COMPUTER_ID}) — config inicial...`);
  const createTx = await computer.create(COMPUTER_ID, "MB-Basic", "GPU-Integrated", "CPU-DualCore", 100n);
  const createReceipt = await createTx.wait();
  const blockCreated = createReceipt.blockNumber as number;
  console.log(`    ✔ ComputerCreated — bloco ${blockCreated} (tx ${createTx.hash})`);

  console.log("==> deposit(100) — funding inicial proporcional ao poder inicial...");
  const initialDepositTx = await vault.deposit(100n);
  const initialDepositReceipt = await initialDepositTx.wait();
  console.log(`    ✔ Deposited(100) — bloco ${initialDepositReceipt.blockNumber} (tx ${initialDepositTx.hash})`);

  // --- Rodada 1 (bloco N+5): +150 -----------------------------------------------------------------
  const targetChange1 = blockCreated + 5;
  await mineEmptyBlocksUntilJustBefore(provider, targetChange1);
  console.log(`\n==> changeParts #1 (alvo bloco ${targetChange1}): +150 (GPU/CPU melhores)...`);
  const change1Tx = await computer.changeParts(COMPUTER_ID, "MB-Basic", "GPU-Discrete-Mid", "CPU-QuadCore", 150n);
  const change1Receipt = await change1Tx.wait();
  const blockChange1 = change1Receipt.blockNumber as number;
  console.log(`    ✔ PartsChanged(+150, newPower=250) — bloco ${blockChange1} (tx ${change1Tx.hash})`);
  const deposit1Tx = await vault.deposit(150n);
  const deposit1Receipt = await deposit1Tx.wait();
  console.log(`    ✔ Deposited(150) — bloco ${deposit1Receipt.blockNumber} (tx ${deposit1Tx.hash})`);

  // --- Rodada 2 (bloco N+20): +300 — este é o bloco "do meio" que prova o cenário -----------------
  const targetChange2 = blockCreated + 20;
  await mineEmptyBlocksUntilJustBefore(provider, targetChange2);
  console.log(`\n==> changeParts #2 (alvo bloco ${targetChange2}): +300 (upgrade pra placa/GPU topo)...`);
  const change2Tx = await computer.changeParts(
    COMPUTER_ID,
    "MB-Enthusiast",
    "GPU-Discrete-High",
    "CPU-QuadCore",
    300n,
  );
  const change2Receipt = await change2Tx.wait();
  const blockChange2 = change2Receipt.blockNumber as number;
  console.log(`    ✔ PartsChanged(+300, newPower=550) — bloco ${blockChange2} (tx ${change2Tx.hash})`);
  const deposit2Tx = await vault.deposit(300n);
  const deposit2Receipt = await deposit2Tx.wait();
  const blockDeposit2 = deposit2Receipt.blockNumber as number;
  console.log(`    ✔ Deposited(300) — bloco ${blockDeposit2} (tx ${deposit2Tx.hash})  <<< bloco-prova`);

  // --- Rodada 3 (bloco N+40): -100 (saca) — config final, precisa ser visivelmente diferente ------
  const targetChange3 = blockCreated + 40;
  await mineEmptyBlocksUntilJustBefore(provider, targetChange3);
  console.log(`\n==> changeParts #3 (alvo bloco ${targetChange3}): -100 (troca CPU, perde poder)...`);
  const change3Tx = await computer.changeParts(
    COMPUTER_ID,
    "MB-Enthusiast",
    "GPU-Discrete-High",
    "CPU-OctaCore",
    -100n,
  );
  const change3Receipt = await change3Tx.wait();
  const blockChange3 = change3Receipt.blockNumber as number;
  console.log(`    ✔ PartsChanged(-100, newPower=450) — bloco ${blockChange3} (tx ${change3Tx.hash})`);
  const withdraw3Tx = await vault.withdraw(100n);
  const withdraw3Receipt = await withdraw3Tx.wait();
  const blockWithdraw3 = withdraw3Receipt.blockNumber as number;
  console.log(`    ✔ Withdrawn(100) — bloco ${blockWithdraw3} (tx ${withdraw3Tx.hash})  <<< bloco final`);

  const configAtProofBlock: ConfigSnapshot = {
    motherboard: "MB-Enthusiast",
    gpu: "GPU-Discrete-High",
    cpu: "CPU-QuadCore",
    power: "550",
  };
  const configFinal: ConfigSnapshot = {
    motherboard: "MB-Enthusiast",
    gpu: "GPU-Discrete-High",
    cpu: "CPU-OctaCore",
    power: "450",
  };

  mkdirSync(LOG_DIR, { recursive: true });
  writeFileSync(
    SCENARIO_STATE_FILE,
    JSON.stringify(
      {
        computerAddress: computerDeployed.address,
        vaultProxyAddress,
        ownerAddress,
        computerId: COMPUTER_ID.toString(),
        blocks: {
          computerCreated: blockCreated,
          depositInitial: initialDepositReceipt.blockNumber,
          change1: blockChange1,
          deposit1: deposit1Receipt.blockNumber,
          change2: blockChange2,
          deposit2ProofBlock: blockDeposit2,
          change3: blockChange3,
          withdraw3Final: blockWithdraw3,
        },
        configAtProofBlock,
        configFinal,
      },
      null,
      2,
    ),
  );

  console.log("\nPronto. Resumo gravado em", SCENARIO_STATE_FILE);
  console.log("\nPróximos passos no Evex (cenário de validação oficial da E19):");
  console.log(`  1. Rode a ação "Registrar os 4 contratos no Evex" de novo (agora registra 5, incluindo o Computador)`);
  console.log(`     — ou cadastre manualmente: endereço ${computerDeployed.address}, rede TESTNET.`);
  console.log(`  2. Confirme que o cofre (${vaultProxyAddress}) já está cadastrado com as 2 versões de ABI (E20).`);
  console.log(`  3. Crie um Perfil de Enriquecimento apontando pro Computador:`);
  console.log(`       targetContractAddress = ${computerDeployed.address}`);
  console.log(`       methodName = getConfig, args = ["${COMPUTER_ID}"] (literal — o evento Deposited/Withdrawn`);
  console.log(`       do Cofre não carrega o id do computador, por isso o argumento é fixo nesta rodada de teste,`);
  console.log(`       não um {{decodedParameters.x}}).`);
  console.log(`       outputs: 4 mapeamentos por posição (motherboard=0, gpu=1, cpu=2, power=3).`);
  console.log(`  4. Crie/edite um webhook no evento Deposited **ou** Withdrawn do Cofre (${vaultProxyAddress}),`);
  console.log(`     referenciando o Perfil criado no passo 3, com um payloadTemplate usando`);
  console.log(`     {{enriched.motherboard}}/{{enriched.gpu}}/{{enriched.cpu}}/{{enriched.power}}.`);
  console.log(`  5. Rode POST /v1/redecode (ou espere o poller) pra garantir que os eventos do Cofre`);
  console.log(`     e do Computador estão decodificados antes de disparar/conferir as entregas.`);
  console.log(`  6. A prova: a entrega do Deposited do bloco ${blockDeposit2} tem que trazer`);
  console.log(`     ${JSON.stringify(configAtProofBlock)} — visivelmente diferente da config final`);
  console.log(`     (bloco ${blockWithdraw3}: ${JSON.stringify(configFinal)}). Se o eth_call usasse o bloco`);
  console.log(`     errado (ex. o atual), as 2 entregas trariam a config final igual — o teste pegaria na hora.`);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
