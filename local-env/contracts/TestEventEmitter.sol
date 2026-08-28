// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @notice Contrato 100% customizado (sem herdar padrão nenhum) para exercitar cenários de
/// decodificação de ABI que ERC20/721/1155 não cobrem: string e array dinâmico não-indexados,
/// tipos menores (uint8/bool) e múltiplos eventos/topic0 distintos no mesmo contrato.
/// @dev Ambiente de teste apenas (ver local-env/CLAUDE.md). Não é detectável pela detecção
/// automática de interface da E6 (não é ERC20/721/1155) — serve para testar o caminho de
/// cadastro manual de ABI (upload/texto, E3).
contract TestEventEmitter {
    event WidgetCreated(uint256 indexed id, address indexed creator, string name, uint256 value);
    event WidgetStatusChanged(uint256 indexed id, uint8 previousStatus, uint8 newStatus, bool flagged);
    event BatchProcessed(address indexed operator, uint256[] ids, uint256 totalValue);

    uint256 private nextId;

    /// @notice Cria um "widget" e emite `WidgetCreated`. Não guarda estado além do contador de id.
    function createWidget(string calldata name, uint256 value) external returns (uint256 id) {
        id = ++nextId;
        emit WidgetCreated(id, msg.sender, name, value);
    }

    /// @notice Emite `WidgetStatusChanged` — não valida a transição, é só pra gerar o evento.
    function changeStatus(uint256 id, uint8 previousStatus, uint8 newStatus, bool flagged) external {
        emit WidgetStatusChanged(id, previousStatus, newStatus, flagged);
    }

    /// @notice Emite `BatchProcessed` com um array dinâmico não-indexado.
    function processBatch(uint256[] calldata ids, uint256 totalValue) external {
        emit BatchProcessed(msg.sender, ids, totalValue);
    }
}
