// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Initializable} from "@openzeppelin/contracts/proxy/utils/Initializable.sol";
import {UUPSUpgradeable} from "@openzeppelin/contracts/proxy/utils/UUPSUpgradeable.sol";

/// @notice V1 de um "cofre" de teste upgradeável (padrão UUPS/ERC1967) — usado para validar de ponta
/// a ponta o cenário 11 do guia de uso (ABI versionada por faixa de blocos). Só existe `deposit()`
/// nesta versão; `TestUpgradeableVaultV2.sol` acrescenta `withdraw()` sem quebrar o layout de storage.
/// @dev Fica atrás de um `TestUpgradeableVaultProxy` (ERC1967Proxy) — nunca é usado diretamente.
/// Ambiente de teste apenas (ver local-env/CLAUDE.md): sem controle de acesso no upgrade de propósito,
/// mesmo princípio dos demais contratos de local-env/contracts/ (nenhum reflete padrão de produção).
contract TestUpgradeableVaultV1 is Initializable, UUPSUpgradeable {
    /// @notice Emitido a cada depósito. Evento "X" do cenário 11 — existe em V1 e em V2.
    event Deposited(address indexed user, uint256 amount);

    /// @dev Slot 0 do layout de storage — V2 mantém esta variável na mesma posição (regra de upgrade
    /// seguro UUPS: só apensar variáveis novas, nunca remover/reordenar as existentes).
    mapping(address => uint256) public balanceOf;

    /// @dev Implementação nunca é inicializada diretamente, só através do proxy (`data` do construtor
    /// de `TestUpgradeableVaultProxy` chama esta função via delegatecall).
    constructor() {
        _disableInitializers();
    }

    function initialize() external initializer {}

    /// @notice Deposita `amount` "unidades" (contabilidade simbólica, sem token real) na conta do
    /// chamador e emite `Deposited`.
    function deposit(uint256 amount) external {
        balanceOf[msg.sender] += amount;
        emit Deposited(msg.sender, amount);
    }

    /// @dev Sem controle de acesso de propósito (ambiente de teste) — qualquer conta pode chamar
    /// `upgradeToAndCall` (herdado de UUPSUpgradeable) e trocar a implementação por trás do proxy.
    function _authorizeUpgrade(address) internal override {}
}
