/**
 * Roteiro do cenário de validação do Modelo de Entidade (E25, `docs/roadmap.md`) — prova que dá pra
 * acumular campos de **eventos diferentes** sobre a mesma entidade lógica ao longo do tempo (o padrão
 * "Created→Approved→Minted" que motivou o design original da E19, agora com um cenário real em vez de
 * inventado).
 *
 * Cria um `TestComputer`, cadastra 2 jogos no `TestGameLibrary` (um com poder exigido baixo, outro
 * alto), tenta rodar o jogo "alto" com o poder inicial (reverte com `InsufficientPower` — motivo de
 * revert real, também útil pro Explorador de ABI, E24), aumenta o poder via `changeParts()` direto (sem
 * mexer no Cofre — esse cenário é sobre o Computador + a Biblioteca de Jogos, não sobre a E19 "Perfil
 * de Enriquecimento" já coberta por `computer-vault-scenario.ts`), e roda de novo (sucesso,
 * `GameRunSucceeded`).
 *
 * 3 eventos diferentes, mesma correlação (`computerId`/`id`): `ComputerCreated` → `PartsChanged` →
 * `GameRunSucceeded`. Um Modelo de Entidade com esses 3 contribuintes acumula `owner`/`initialPower`
 * (do 1º), `motherboard`/`gpu`/`cpu`/`currentPower` (do 2º) e `lastGameName`/`lastGamePower` (do 3º) —
 * um webhook no `GameRunSucceeded` referenciando esse modelo expõe tudo junto via `{{model.*}}`.
 *
 * Pré-requisitos (nesta ordem): `deploy` (com `TestComputer.sol`/`TestGameLibrary.sol` já copiados) →
 * `deploy-game-library.ts`.
 *
 * Uso: `npx tsx local-env/seed/game-library-scenario.ts` (de dentro de `local-env/`).
 *
 * Variáveis de ambiente:
 *   RPC_URL               default "http://127.0.0.1:8545"
 *   DEPLOYER_PRIVATE_KEY  default conta 0 do Hardhat
 *   SCAFFOLD_ETH_DIR      default "./local-env-scaffold-eth-2" (relativo a este script)
 *   CHAIN_ID              default "31337"
 *   COMPUTER_ID           default "1000" — id novo de propósito (bem longe dos ids usados por
 *                         computer-vault-scenario.ts), pra não colidir com computadores já existentes
 *   EASY_GAME_ID          default "1" — jogo que já roda com o poder inicial
 *   HARD_GAME_ID          default "2" — jogo que exige mais poder do que o inicial (prova o revert)
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
const COMPUTER_ID = BigInt(process.env.COMPUTER_ID ?? "1000");
const EASY_GAME_ID = BigInt(process.env.EASY_GAME_ID ?? "1");
const HARD_GAME_ID = BigInt(process.env.HARD_GAME_ID ?? "2");

const INITIAL_POWER = 100n;
const EASY_GAME_REQUIRED_POWER = 50n;
const HARD_GAME_REQUIRED_POWER = 500n;
const POWER_BOOST = 500n; // suficiente pra passar de 100 pra >=500 (folga proposital, não exato)

const LOCAL_ENV_DIR = resolve(import.meta.dirname, "..");
const LOG_DIR = resolve(LOCAL_ENV_DIR, ".logs");
const LIBRARY_STATE_FILE = resolve(LOG_DIR, "game-library.json");
const SCENARIO_STATE_FILE = resolve(LOG_DIR, "game-library-scenario.json");

const hardhatRequire = createRequire(pathToFileURL(resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/package.json")).href);
const { Contract, Interface, JsonRpcProvider, NonceManager, Wallet } = hardhatRequire("ethers") as typeof import("ethers");

type DeployedContract = { address: string; abi: unknown[] };
type DeployedContracts = Record<string, Record<string, DeployedContract>>;

async function loadDeployedContracts(): Promise<DeployedContracts> {
  const deployedContractsPath = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(deployedContractsPath).href);
  return module.default as DeployedContracts;
}

function loadGameLibrary(): { address: string } {
  if (!existsSync(LIBRARY_STATE_FILE)) {
    throw new Error(`Nenhuma TestGameLibrary conhecida (${LIBRARY_STATE_FILE} não existe). Rode deploy-game-library.ts antes.`);
  }
  return JSON.parse(readFileSync(LIBRARY_STATE_FILE, "utf8")) as { address: string };
}

async function main(): Promise<void> {
  const provider = new JsonRpcProvider(RPC_URL);
  const signer = new NonceManager(new Wallet(DEPLOYER_PRIVATE_KEY, provider));

  const deployedContracts = await loadDeployedContracts();
  const computerDeployed = deployedContracts[CHAIN_ID]?.TestComputer;
  if (!computerDeployed) {
    throw new Error(`TestComputer não encontrado em deployedContracts.ts pro chainId ${CHAIN_ID}. Rode "yarn deploy --tags TestContracts" antes.`);
  }
  const libraryState = loadGameLibrary();
  // A ABI de TestGameLibrary não está em deployedContracts.ts (deployado fora do rocketh) — lê do
  // artifact do Hardhat direto, mesmo padrão de deploy-game-library.ts.
  const libraryArtifactPath = resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/artifacts/contracts/TestGameLibrary.sol/TestGameLibrary.json");
  const libraryArtifact = hardhatRequire(libraryArtifactPath) as { abi: unknown[] };

  const computer = new Contract(computerDeployed.address, computerDeployed.abi, signer);
  const library = new Contract(libraryState.address, libraryArtifact.abi, signer);
  const libraryInterface = new Interface(libraryArtifact.abi);

  console.log(`==> Computador: ${computerDeployed.address} | Biblioteca de jogos: ${libraryState.address}`);

  console.log(`\n==> create(id=${COMPUTER_ID}) — power inicial ${INITIAL_POWER}...`);
  const createTx = await computer.create(COMPUTER_ID, "MB-Basic", "GPU-Integrated", "CPU-DualCore", INITIAL_POWER);
  const createReceipt = await createTx.wait();
  console.log(`    ✔ ComputerCreated — bloco ${createReceipt.blockNumber}`);

  console.log(`\n==> registerGame(${EASY_GAME_ID}, "Tetris Clássico", requiredPower=${EASY_GAME_REQUIRED_POWER})...`);
  const registerEasyTx = await library.registerGame(EASY_GAME_ID, "Tetris Clássico", EASY_GAME_REQUIRED_POWER);
  await registerEasyTx.wait();
  console.log(`\n==> registerGame(${HARD_GAME_ID}, "Cyberpunk Ultra HD", requiredPower=${HARD_GAME_REQUIRED_POWER})...`);
  const registerHardTx = await library.registerGame(HARD_GAME_ID, "Cyberpunk Ultra HD", HARD_GAME_REQUIRED_POWER);
  await registerHardTx.wait();

  console.log(`\n==> runGame(${COMPUTER_ID}, ${EASY_GAME_ID}) — poder inicial já é suficiente...`);
  const easyRunTx = await library.runGame(COMPUTER_ID, EASY_GAME_ID);
  const easyRunReceipt = await easyRunTx.wait();
  console.log(`    ✔ GameRunSucceeded (Tetris Clássico) — bloco ${easyRunReceipt.blockNumber}`);

  console.log(`\n==> runGame(${COMPUTER_ID}, ${HARD_GAME_ID}) — esperado reverter (poder insuficiente)...`);
  let revertedAsExpected = false;
  try {
    await library.runGame(COMPUTER_ID, HARD_GAME_ID);
    console.log("    ✘ inesperado: não reverteu!");
  } catch (error) {
    const decoded = libraryInterface.parseError((error as { data?: string }).data ?? "0x");
    if (decoded?.name === "InsufficientPower") {
      revertedAsExpected = true;
      console.log(`    ✔ reverteu como esperado: InsufficientPower(required=${decoded.args[0]}, actual=${decoded.args[1]})`);
    } else {
      throw error;
    }
  }
  if (!revertedAsExpected) throw new Error("runGame do jogo difícil deveria ter revertido com InsufficientPower e não reverteu.");

  // Achado real (E25): o `NonceManager` do ethers incrementa o nonce otimisticamente ao popular a
  // transação, antes de saber que ela vai reverter na simulação (`eth_estimateGas`) e nunca chegar a
  // ser enviada de verdade — sem o `reset()` abaixo, a próxima transação real usaria um nonce "furado"
  // e o Hardhat rejeitaria com "Nonce too high" (automining não permite fila de nonce fora de ordem).
  signer.reset();

  console.log(`\n==> changeParts(+${POWER_BOOST}) — aumenta o poder o suficiente pro jogo difícil...`);
  const changeTx = await computer.changeParts(COMPUTER_ID, "MB-Enthusiast", "GPU-Discrete-High", "CPU-OctaCore", POWER_BOOST);
  const changeReceipt = await changeTx.wait();
  const newPower = INITIAL_POWER + POWER_BOOST;
  console.log(`    ✔ PartsChanged(+${POWER_BOOST}, newPower=${newPower}) — bloco ${changeReceipt.blockNumber}`);

  console.log(`\n==> runGame(${COMPUTER_ID}, ${HARD_GAME_ID}) de novo — agora com poder suficiente...`);
  const hardRunTx = await library.runGame(COMPUTER_ID, HARD_GAME_ID);
  const hardRunReceipt = await hardRunTx.wait();
  console.log(`    ✔ GameRunSucceeded (Cyberpunk Ultra HD) — bloco ${hardRunReceipt.blockNumber} <<< evento-prova do Modelo de Entidade`);

  mkdirSync(LOG_DIR, { recursive: true });
  writeFileSync(
    SCENARIO_STATE_FILE,
    JSON.stringify(
      {
        computerAddress: computerDeployed.address,
        libraryAddress: libraryState.address,
        computerId: COMPUTER_ID.toString(),
        easyGameId: EASY_GAME_ID.toString(),
        hardGameId: HARD_GAME_ID.toString(),
        blocks: {
          computerCreated: createReceipt.blockNumber,
          easyGameRun: easyRunReceipt.blockNumber,
          partsChanged: changeReceipt.blockNumber,
          hardGameRunProof: hardRunReceipt.blockNumber,
        },
      },
      null,
      2,
    ),
  );

  console.log("\nPronto. Resumo gravado em", SCENARIO_STATE_FILE);
  console.log("\nPróximos passos no Evex (cenário de validação da E25):");
  console.log(`  1. Cadastre o TestGameLibrary (${libraryState.address}) se ainda não tiver feito (ver deploy-game-library.ts).`);
  console.log(`  2. Crie um Modelo de Entidade com 3 contribuintes, todos correlacionados por "id"/"computerId":`);
  console.log(`       - ${computerDeployed.address} / ComputerCreated / correlationParam=id / mapeia owner, initialPower`);
  console.log(`       - ${computerDeployed.address} / PartsChanged / correlationParam=id / mapeia motherboard, gpu, cpu, newPower→currentPower`);
  console.log(`       - ${libraryState.address} / GameRunSucceeded / correlationParam=computerId / mapeia gameName→lastGameName, power→lastGamePower`);
  console.log(`  3. Crie um webhook no evento GameRunSucceeded do TestGameLibrary, referenciando esse Modelo,`);
  console.log(`     com payloadTemplate usando {{model.owner}}/{{model.motherboard}}/{{model.currentPower}}/{{model.lastGameName}}.`);
  console.log(`  4. A prova: a entrega do bloco ${hardRunReceipt.blockNumber} (GameRunSucceeded do Cyberpunk) tem que trazer`);
  console.log(`     os campos de TODOS os 3 eventos juntos — owner/initialPower de quando o computador foi criado,`);
  console.log(`     motherboard/gpu/cpu/currentPower da troca de peça, e lastGameName/lastGamePower do próprio evento.`);
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
