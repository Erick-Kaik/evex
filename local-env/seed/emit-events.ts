/**
 * Dispara eventos reais nos 4 contratos de teste direto via script — sem precisar subir a UI
 * `/debug` do scaffold-eth-2 (`yarn start`) só para clicar em botões manualmente. Substitui esse uso
 * pontual da UI (ver DEC-059); a UI continua disponível manualmente (`yarn start`, ver README §3.7)
 * para quem preferir clicar em vez de rodar o script.
 *
 * Lê `packages/nextjs/contracts/deployedContracts.ts` (mesmo loader de `register-contracts.ts`) para
 * pegar endereço + ABI de cada contrato, assina com a conta deployer padrão do Hardhat (mesma usada
 * pelo deploy, `hardhat.config.ts` do scaffold-eth-2 — chave pública/conhecida de qualquer projeto
 * Hardhat local, não é segredo) e chama as funções que emitem os eventos que o Evex indexa.
 *
 * `local-env/` não tem `package.json` próprio (nenhum script daqui precisava de dependência além do
 * `fetch`/`import()` nativos até agora) — em vez de criar um só pra `ethers`, reaproveita a instalação
 * que o clone do scaffold-eth-2 já tem (`packages/hardhat` lista `ethers` como dependência), resolvida
 * via `createRequire` escopado no `package.json` dele. Mesmo princípio de "não duplicar o que o clone
 * já resolve" já usado pelo loader de `deployedContracts.ts` abaixo.
 *
 * Uso: `npx tsx local-env/seed/emit-events.ts` (rodar de dentro de `local-env/`, depois de
 * `yarn deploy --tags TestContracts` no clone do scaffold-eth-2 — ou simplesmente via `up.sh`).
 *
 * Variáveis de ambiente:
 *   RPC_URL               default "http://127.0.0.1:8545" (nó Hardhat local)
 *   DEPLOYER_PRIVATE_KEY  default conta 0 do Hardhat (mesma default de hardhat.config.ts)
 *   RECIPIENT_ADDRESS     default conta 1 do Hardhat — só o endereço, não precisa assinar nada;
 *                         usado como "to" dos mints, pra Transfer sair com holder != deployer
 *   SCAFFOLD_ETH_DIR      default "./local-env-scaffold-eth-2" (relativo a este script)
 *   CHAIN_ID              default "31337"
 *   ROUNDS                default "1" — quantas vezes repetir o lote inteiro (útil pra gerar volume
 *                         de eventos, ex. testar paginação de GET /v1/events)
 */
import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";
import type { Wallet as EthersWallet } from "ethers";

const RPC_URL = process.env.RPC_URL ?? "http://127.0.0.1:8545";
const DEPLOYER_PRIVATE_KEY =
  process.env.DEPLOYER_PRIVATE_KEY ?? "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
const RECIPIENT_ADDRESS = process.env.RECIPIENT_ADDRESS ?? "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
const SCAFFOLD_ETH_DIR = process.env.SCAFFOLD_ETH_DIR ?? resolve(import.meta.dirname, "../local-env-scaffold-eth-2");
const CHAIN_ID = process.env.CHAIN_ID ?? "31337";
const ROUNDS = Number.parseInt(process.env.ROUNDS ?? "1", 10);

const hardhatRequire = createRequire(pathToFileURL(resolve(SCAFFOLD_ETH_DIR, "packages/hardhat/package.json")).href);
const { Contract, JsonRpcProvider, Wallet } = hardhatRequire("ethers") as typeof import("ethers");

type DeployedContract = { address: string; abi: unknown[] };
type DeployedContracts = Record<string, Record<string, DeployedContract>>;

async function loadDeployedContracts(): Promise<DeployedContracts> {
  const deployedContractsPath = resolve(SCAFFOLD_ETH_DIR, "packages/nextjs/contracts/deployedContracts.ts");
  const module = await import(pathToFileURL(deployedContractsPath).href);
  return module.default as DeployedContracts;
}

/** Dispara um round de eventos variados nos 4 contratos. Cada round é seguro de repetir (ids/nonces
 * avançam sozinhos nos contratos — não há estado "já mintado" a colidir). */
async function emitRound(contracts: Record<string, DeployedContract>, signer: EthersWallet, round: number): Promise<void> {
  const erc20 = new Contract(contracts.TestERC20.address, contracts.TestERC20.abi, signer);
  const erc721 = new Contract(contracts.TestERC721.address, contracts.TestERC721.abi, signer);
  const erc1155 = new Contract(contracts.TestERC1155.address, contracts.TestERC1155.abi, signer);
  const eventEmitter = new Contract(contracts.TestEventEmitter.address, contracts.TestEventEmitter.abi, signer);

  const amount = 1_000n * BigInt(round);

  await confirm("TestERC20.mint", erc20.mint(RECIPIENT_ADDRESS, amount));
  await confirm("TestERC721.safeMint", erc721.safeMint(RECIPIENT_ADDRESS));
  await confirm("TestERC1155.mint", erc1155.mint(RECIPIENT_ADDRESS, 1n, amount, "0x"));
  await confirm(
    "TestERC1155.mintBatch",
    erc1155.mintBatch(RECIPIENT_ADDRESS, [2n, 3n], [amount, amount * 2n], "0x"),
  );
  await confirm(
    "TestEventEmitter.createWidget",
    eventEmitter.createWidget(`widget-round-${round}`, amount),
  );
  await confirm(
    "TestEventEmitter.changeStatus",
    eventEmitter.changeStatus(round, 0, 1, false),
  );
  await confirm(
    "TestEventEmitter.processBatch",
    eventEmitter.processBatch([1n, 2n, 3n], amount),
  );
}

async function confirm(label: string, txPromise: Promise<{ hash: string; wait: () => Promise<unknown> }>): Promise<void> {
  const tx = await txPromise;
  await tx.wait();
  console.log(`  ✔ ${label} (tx ${tx.hash})`);
}

async function main(): Promise<void> {
  const provider = new JsonRpcProvider(RPC_URL);
  const signer = new Wallet(DEPLOYER_PRIVATE_KEY, provider);

  const deployedContracts = await loadDeployedContracts();
  const contractsOnChain = deployedContracts[CHAIN_ID];
  if (!contractsOnChain) {
    throw new Error(
      `Nenhum contrato deployado encontrado para o chainId ${CHAIN_ID} em deployedContracts.ts. ` +
        `Rode "yarn deploy --tags TestContracts" no scaffold-eth-2 antes.`,
    );
  }

  for (let round = 1; round <= ROUNDS; round++) {
    console.log(`Round ${round}/${ROUNDS} — disparando eventos como ${signer.address}...`);
    await emitRound(contractsOnChain, signer, round);
  }
  console.log("Pronto. Confira em GET /v1/events (ou no frontend do Evex).");
}

main().catch(error => {
  console.error(error);
  process.exit(1);
});
