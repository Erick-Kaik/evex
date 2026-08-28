// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @notice Token ERC20 de teste para o Evex — mint público, sem controle de acesso.
/// @dev Ambiente de teste apenas (ver local-env/CLAUDE.md); nunca usar este padrão em produção.
contract TestERC20 is ERC20 {
    constructor(string memory name, string memory symbol) ERC20(name, symbol) {}

    /// @notice Minta `amount` tokens para `to`. Sem restrição — qualquer conta pode chamar.
    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }
}
