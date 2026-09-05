// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {Initializable} from "@openzeppelin/contracts/proxy/utils/Initializable.sol";
import {UUPSUpgradeable} from "@openzeppelin/contracts/proxy/utils/UUPSUpgradeable.sol";

/// @notice V2 do "cofre" de teste upgradeável — MESMO layout de storage do V1 (`balanceOf` no mesmo
/// slot 0; regra de upgrade seguro UUPS: só apensar variáveis novas, nunca remover/reordenar as
/// antigas). Mantém `deposit()`/`Deposited` (evento "X") e acrescenta `withdraw()`/`Withdrawn`
/// (evento "Y") — é o par que o cenário 11 do guia de uso usa para provar a ABI versionada por faixa
/// de blocos: eventos de antes do upgrade só podem ser `Deposited`, os de depois podem ser os dois.
/// @dev Fica atrás do mesmo `TestUpgradeableVaultProxy` que já rodava com V1 — trocado via
/// `upgradeToAndCall` (herdado de UUPSUpgradeable), nunca redeployado do zero. Ambiente de teste
/// apenas — ver `TestUpgradeableVaultV1.sol`.
contract TestUpgradeableVaultV2 is Initializable, UUPSUpgradeable {
    event Deposited(address indexed user, uint256 amount);
    /// @notice Emitido a cada saque. Evento "Y" do cenário 11 — só existe a partir desta versão.
    event Withdrawn(address indexed user, uint256 amount);

    /// @dev Mesmo slot 0 de `TestUpgradeableVaultV1` — não reordenar/remover.
    mapping(address => uint256) public balanceOf;

    /// @dev Implementação nunca é inicializada diretamente — o upgrade não chama `initialize` de novo
    /// (o estado do proxy já veio inicializado por V1; `upgradeToAndCall` desta migração passa `data`
    /// vazio de propósito).
    constructor() {
        _disableInitializers();
    }

    function deposit(uint256 amount) external {
        balanceOf[msg.sender] += amount;
        emit Deposited(msg.sender, amount);
    }

    /// @notice Saca `amount` do saldo do chamador (reverte se insuficiente) e emite `Withdrawn`.
    function withdraw(uint256 amount) external {
        require(balanceOf[msg.sender] >= amount, "saldo insuficiente");
        balanceOf[msg.sender] -= amount;
        emit Withdrawn(msg.sender, amount);
    }

    /// @dev Sem controle de acesso de propósito (ambiente de teste) — ver `TestUpgradeableVaultV1`.
    function _authorizeUpgrade(address) internal override {}
}
