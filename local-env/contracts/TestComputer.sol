// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @notice Contrato de teste pra validar o "cenário de validação oficial" da E19 (eventos
/// enriquecidos, `docs/roadmap.md`) — prova que o `eth_call` de um Perfil de Enriquecimento é
/// resolvido **no bloco em que o evento disparador ocorreu**, não no bloco atual/tip da chain.
/// Representa um "computador" com peças trocáveis (`motherboard`/`gpu`/`cpu`) e um
/// `processingPower` que varia a cada troca — o par `local-env/seed/computer-vault-scenario.ts`
/// troca peças em blocos bem espaçados e deposita/saca no `TestUpgradeableVault` (já existente,
/// E20) proporcionalmente a cada variação, simulando "a moeda é o poder de processamento
/// acumulado". O ponto central do cenário: `getConfig(id)` devolve **4 saídas num método só**, de
/// propósito, pra exercitar o mapeamento múltiplo por posição do Perfil de Enriquecimento
/// (`EnrichmentOutputMapping.outputIndex`, DEC-082 item 3).
/// @dev Ambiente de teste apenas (ver local-env/CLAUDE.md) — sem controle de acesso de propósito
/// (`create`/`changeParts` são públicos, qualquer conta pode chamar), mesmo princípio dos demais
/// contratos de `local-env/contracts/`.
contract TestComputer {
    struct Computer {
        address owner;
        string motherboard;
        string gpu;
        string cpu;
        uint256 processingPower;
    }

    /// @notice Emitido na criação de um computador. `initialPower` é o `processingPower` inicial.
    event ComputerCreated(
        uint256 indexed id,
        address indexed owner,
        string motherboard,
        string gpu,
        string cpu,
        uint256 initialPower
    );

    /// @notice Emitido a cada troca de peça(s). `powerDelta` é assinado (positivo = ganhou poder,
    /// negativo = perdeu) — é o campo que `computer-vault-scenario.ts` usa pra decidir
    /// depositar (`> 0`) ou sacar (`< 0`) no cofre. `newPower` é o `processingPower` já atualizado.
    event PartsChanged(
        uint256 indexed id,
        string motherboard,
        string gpu,
        string cpu,
        int256 powerDelta,
        uint256 newPower
    );

    mapping(uint256 => Computer) private computers;

    /// @notice Cria o computador `id` com as peças e `processingPower` inicial informados.
    /// @dev Não valida `id` duplicado de propósito (ambiente de teste, contrato descartável) —
    /// sobrescrever um `id` existente reseta o estado dele sem emitir `PartsChanged`.
    function create(
        uint256 id,
        string calldata motherboard,
        string calldata gpu,
        string calldata cpu,
        uint256 initialPower
    ) external {
        computers[id] = Computer(msg.sender, motherboard, gpu, cpu, initialPower);
        emit ComputerCreated(id, msg.sender, motherboard, gpu, cpu, initialPower);
    }

    /// @notice Troca as peças do computador `id` e aplica `powerDelta` ao `processingPower` atual.
    /// @dev Reverte se o computador não existir (`owner == address(0)`) ou se o delta deixaria o
    /// poder negativo — `int256`/`uint256` misturados de propósito pra exercitar o tipo Solidity
    /// `int256` no catálogo de eventos (nenhum outro contrato de teste do `local-env` usa inteiro
    /// assinado ainda).
    function changeParts(
        uint256 id,
        string calldata motherboard,
        string calldata gpu,
        string calldata cpu,
        int256 powerDelta
    ) external {
        Computer storage c = computers[id];
        require(c.owner != address(0), "computador inexistente");

        int256 newPowerSigned = int256(c.processingPower) + powerDelta;
        require(newPowerSigned >= 0, "processingPower nao pode ficar negativo");
        uint256 newPower = uint256(newPowerSigned);

        c.motherboard = motherboard;
        c.gpu = gpu;
        c.cpu = cpu;
        c.processingPower = newPower;

        emit PartsChanged(id, motherboard, gpu, cpu, powerDelta, newPower);
    }

    /// @notice Config atual do computador `id` — 1 método, 4 saídas (motherboard/gpu/cpu/power),
    /// o alvo do `EnrichmentCall.methodName` do cenário de validação oficial da E19.
    function getConfig(uint256 id)
        external
        view
        returns (string memory motherboard, string memory gpu, string memory cpu, uint256 power)
    {
        Computer storage c = computers[id];
        return (c.motherboard, c.gpu, c.cpu, c.processingPower);
    }
}
