// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC1155} from "@openzeppelin/contracts/token/ERC1155/ERC1155.sol";

/// @notice Multi-token ERC1155 de teste para o Evex — mint público, sem controle de acesso.
/// @dev Ambiente de teste apenas (ver local-env/CLAUDE.md); nunca usar este padrão em produção.
contract TestERC1155 is ERC1155 {
    constructor() ERC1155("https://evex.test/api/token/{id}.json") {}

    /// @notice Minta `amount` unidades do token `id` para `to`.
    function mint(address to, uint256 id, uint256 amount, bytes memory data) external {
        _mint(to, id, amount, data);
    }

    /// @notice Minta um lote de tokens (`ids`/`amounts` pareados) para `to`.
    function mintBatch(address to, uint256[] memory ids, uint256[] memory amounts, bytes memory data) external {
        _mintBatch(to, ids, amounts, data);
    }
}
