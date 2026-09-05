// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC1967Proxy} from "@openzeppelin/contracts/proxy/ERC1967/ERC1967Proxy.sol";

/// @notice Proxy ERC1967 "puro" (sem lógica própria) na frente de TestUpgradeableVaultV1/V2 — usado
/// para exercitar o cenário 11 do guia de uso (ABI versionada por faixa de blocos). O endereço deste
/// proxy é o que se cadastra no Evex; a implementação por trás dele troca via `upgradeToAndCall`
/// (herdado de UUPSUpgradeable, implementado na própria implementação, não aqui).
/// @dev Só existe como contrato próprio (em vez de instanciar `ERC1967Proxy` direto num script) porque
/// o Hardhat 3 deste projeto não gera artifact separado para contratos de `node_modules` que não sejam
/// herdados por um contrato de `contracts/` — mesmo princípio que já faz `TestERC20.sol` herdar de
/// `ERC20` da OpenZeppelin em vez de importá-la solta. Ambiente de teste apenas (ver local-env/CLAUDE.md).
contract TestUpgradeableVaultProxy is ERC1967Proxy {
    constructor(address implementation, bytes memory data) ERC1967Proxy(implementation, data) {}
}
