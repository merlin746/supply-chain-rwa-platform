// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

/**
 * @title IAssetCore
 * @dev 成员1 RWA_Core_Asset 合约的接口定义，与实际部署ABI完全对齐
 *      基于ERC-3525(SFT) + OpenZeppelin AccessControl
 */
interface IAssetCore {
    // ========== 角色常量（继承自OpenZeppelin AccessControl） ==========
    function DEFAULT_ADMIN_ROLE() external view returns (bytes32);
    function CORE_ENTERPRISE_ROLE() external view returns (bytes32);
    function SUPPLIER_ROLE() external view returns (bytes32);
    function FINANCIAL_INSTITUTION_ROLE() external view returns (bytes32);
    function AUDITOR_ROLE() external view returns (bytes32);

    // ========== ERC-3525 核心查询 ==========
    function balanceOf(uint256 tokenId) external view returns (uint256);
    function ownerOf(uint256 tokenId) external view returns (address);
    function slotOf(uint256 tokenId) external view returns (uint256);

    // ========== ERC-3525 价值转移（三重重载） ==========
    /// @dev 在两个已有Token间转移价值，要求同Slot
    function transferFrom(uint256 fromTokenId, uint256 toTokenId, uint256 value) external;

    /// @dev 从fromTokenId拆分价值并创建新Token给to地址，返回新TokenId
    function transferFrom(uint256 fromTokenId, address to, uint256 value) external returns (uint256);

    /// @dev NFT所有权转移（整个Token含全部价值）
    function transferFrom(address from, address to, uint256 tokenId) external;
    function safeTransferFrom(address from, address to, uint256 tokenId) external;
    function safeTransferFrom(address from, address to, uint256 tokenId, bytes calldata data) external;

    // ========== 授权 ==========
    function approve(uint256 tokenId, address operator, uint256 value) external;
    function allowance(uint256 tokenId, address operator) external view returns (uint256);
    function approve(address to, uint256 tokenId) external;
    function getApproved(uint256 tokenId) external view returns (address);
    function setApprovalForAll(address operator, bool approved) external;
    function isApprovedForAll(address owner, address operator) external view returns (bool);

    // ========== RWA资产生命周期 ==========
    function mintRWAAsset(
        address to,
        uint256 slot,
        uint256 value,
        uint256 maturityDate,
        string calldata contractHash,
        string calldata uri
    ) external returns (uint256 tokenId);

    function revokeAsset(uint256 tokenId, string calldata reason) external;
    function settleAsset(uint256 tokenId) external;
    function setAssetFrozen(uint256 tokenId, bool frozen) external;

    // ========== RWA查询 ==========
    function getAssetInfo(uint256 tokenId)
        external
        view
        returns (
            uint256 slot,
            address issuer,
            uint256 faceValue,
            uint256 maturityDate,
            uint8 status,
            string memory contractHash,
            string memory uri,
            uint256 currentValue,
            address owner
        );

    function getHeldAssets(address holder) external view returns (uint256[] memory);
    function getIssuedAssets(address issuer) external view returns (uint256[] memory);

    // ========== AccessControl ==========
    function hasRole(bytes32 role, address account) external view returns (bool);
    function grantRole(bytes32 role, address account) external;
    function revokeRole(bytes32 role, address account) external;
}
