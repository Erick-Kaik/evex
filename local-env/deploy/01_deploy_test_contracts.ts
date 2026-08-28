import { deployScript, artifacts } from "../rocketh/deploy.js";

/**
 * Deploya os 4 contratos de teste do Evex (local-env/contracts/) usando a conta deployer padrão
 * do Hardhat. Nenhum mint/evento é disparado aqui de propósito — o estado inicial fica vazio para
 * a demo via `/debug` (aba "Debug Contracts" do scaffold-eth-2) fazer sentido.
 *
 * @param env Rocketh environment object.
 */
export default deployScript(
  async env => {
    const { deployer } = env.namedAccounts;

    await env.deploy("TestERC20", {
      account: deployer,
      artifact: artifacts.TestERC20,
      args: ["Evex Test Token", "EVX"],
    });

    await env.deploy("TestERC721", {
      account: deployer,
      artifact: artifacts.TestERC721,
      args: ["Evex Test NFT", "ENFT"],
    });

    await env.deploy("TestERC1155", {
      account: deployer,
      artifact: artifacts.TestERC1155,
      args: [],
    });

    await env.deploy("TestEventEmitter", {
      account: deployer,
      artifact: artifacts.TestEventEmitter,
      args: [],
    });
  },
  {
    // yarn deploy --tags TestContracts
    tags: ["TestContracts"],
  },
);
