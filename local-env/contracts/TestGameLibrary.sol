// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @notice Face mínima do `TestComputer` que este contrato consulta — só o que `runGame` precisa
/// (`power`, a 4ª saída de `getConfig`). Ver `TestComputer.sol`.
interface ITestComputer {
    function getConfig(uint256 id)
        external
        view
        returns (string memory motherboard, string memory gpu, string memory cpu, uint256 power);
}

/// @notice Contrato de teste pra validar o cenário de "Modelo de Entidade" da E19 (`docs/roadmap.md`,
/// E25) — biblioteca de jogos que só rodam se o `TestComputer` referenciado tiver poder suficiente.
/// `runGame` faz uma chamada **on-chain de verdade** (`ITestComputer.getConfig`, não um valor passado
/// pelo chamador) pra decidir se deixa rodar — prova cross-contract real, não só simulado no Evex.
/// Junto com `ComputerCreated`/`PartsChanged` (`TestComputer.sol`), `GameRunSucceeded` forma o trio de
/// eventos diferentes, correlacionados por `computerId`, que o cenário acumula num Modelo de Entidade.
/// @dev Ambiente de teste apenas (ver local-env/CLAUDE.md) — sem controle de acesso de propósito,
/// mesmo princípio dos demais contratos de `local-env/contracts/`. Deployado via
/// `seed/deploy-game-library.ts` (fora do pipeline `rocketh` de `deploy/01_deploy_test_contracts.ts`,
/// mesmo racional do `TestUpgradeableVault*`: o construtor precisa do endereço do `TestComputer` já
/// deployado, uma dependência de ordem que o script expressa direto em ethers).
contract TestGameLibrary {
    struct Game {
        string name;
        uint256 requiredPower;
        bool registered;
    }

    /// @notice Emitido ao cadastrar um jogo.
    event GameRegistered(uint256 indexed gameId, string name, uint256 requiredPower);

    /// @notice Emitido quando `runGame` roda com sucesso (poder suficiente no momento da chamada).
    /// Evento "de saída" do trio que o Modelo de Entidade acumula — o `power` aqui é o valor **no
    /// bloco desta transação**, não precisa de enriquecimento pra isso (já vem no próprio evento).
    event GameRunSucceeded(uint256 indexed computerId, uint256 indexed gameId, string gameName, uint256 power);

    /// @notice Revertido quando `power` do computador é menor que `requiredPower` do jogo — motivo de
    /// revert real (não fabricado) pra exercitar o Explorador de ABI (E24).
    error InsufficientPower(uint256 required, uint256 actual);

    /// @notice Revertido se `gameId` nunca foi cadastrado via `registerGame`.
    error GameNotFound(uint256 gameId);

    /// @dev Endereço do `TestComputer` consultado por `runGame` — fixado no deploy (`immutable`),
    /// nunca muda depois (ambiente de teste, sem necessidade de um setter).
    address public immutable computer;

    mapping(uint256 => Game) private games;

    constructor(address computerAddress) {
        computer = computerAddress;
    }

    /// @notice Cadastra (ou sobrescreve) o jogo `gameId` com o poder mínimo exigido pra rodar.
    function registerGame(uint256 gameId, string calldata name, uint256 requiredPower) external {
        games[gameId] = Game(name, requiredPower, true);
        emit GameRegistered(gameId, name, requiredPower);
    }

    /// @notice Tenta rodar `gameId` no computador `computerId` — consulta o `processingPower` atual
    /// via `ITestComputer.getConfig` (chamada real, não simulada) e reverte com `InsufficientPower`
    /// se não for suficiente. Sem efeito colateral no `TestComputer` (só leitura) nem no `Game`
    /// cadastrado — só emite o evento em caso de sucesso.
    function runGame(uint256 computerId, uint256 gameId) external {
        Game memory game = games[gameId];
        if (!game.registered) revert GameNotFound(gameId);

        (, , , uint256 power) = ITestComputer(computer).getConfig(computerId);
        if (power < game.requiredPower) revert InsufficientPower(game.requiredPower, power);

        emit GameRunSucceeded(computerId, gameId, game.name, power);
    }
}
