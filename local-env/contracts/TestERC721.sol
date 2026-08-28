// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {ERC721} from "@openzeppelin/contracts/token/ERC721/ERC721.sol";

/// @notice NFT ERC721 de teste para o Evex — mint público, sem controle de acesso.
/// @dev Ambiente de teste apenas (ver local-env/CLAUDE.md); nunca usar este padrão em produção.
contract TestERC721 is ERC721 {
    uint256 private nextId;

    constructor(string memory name, string memory symbol) ERC721(name, symbol) {}

    /// @notice Minta o próximo tokenId sequencial para `to`.
    /// @dev Sem estado por-endereço a proteger (ex.: "já mintou"), então o vetor de reentrância
    /// clássico de `_safeMint` (ver skill erc-721 do scaffold-eth-2) não se aplica aqui.
    function safeMint(address to) external returns (uint256 id) {
        id = ++nextId;
        _safeMint(to, id);
    }
}
