// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import "../../interfaces/IAssetCore.sol";

/**
 * @title MockAssetCore
 * @dev 成员1 RWA_Core_Asset 的简化Mock，与实际ABI对齐，用于成员2合约单元测试
 *      实现 ERC-3525 价值转移、NFT转移、AccessControl角色、资产生命周期
 */
contract MockAssetCore is IAssetCore {
    // ========== 角色常量 ==========
    bytes32 public constant override DEFAULT_ADMIN_ROLE = 0x00;
    bytes32 public constant override CORE_ENTERPRISE_ROLE = keccak256("CORE_ENTERPRISE_ROLE");
    bytes32 public constant override SUPPLIER_ROLE = keccak256("SUPPLIER_ROLE");
    bytes32 public constant override FINANCIAL_INSTITUTION_ROLE = keccak256("FINANCIAL_INSTITUTION_ROLE");
    bytes32 public constant override AUDITOR_ROLE = keccak256("AUDITOR_ROLE");

    // ========== 资产状态 ==========
    enum AssetStatus { Active, Frozen, Revoked, Settled }

    struct TokenData {
        address owner;
        uint256 value;
        uint256 slot;
        address issuer;
        uint256 faceValue;
        uint256 maturityDate;
        AssetStatus status;
        string contractHash;
        string metadataURI;
        bool exists;
    }

    mapping(uint256 => TokenData) private tokens;
    uint256 private _nextTokenId = 1;

    // ========== 授权 ==========
    mapping(uint256 => mapping(address => uint256)) private _valueApprovals;
    mapping(address => mapping(address => bool)) private _operatorApprovals;
    mapping(uint256 => address) private _tokenApprovals;

    // ========== 角色 ==========
    mapping(bytes32 => mapping(address => bool)) private roles;

    constructor() {
        roles[DEFAULT_ADMIN_ROLE][msg.sender] = true;
    }

    // ========== 辅助函数（测试用） ==========
    function setRole(bytes32 role, address account, bool enabled) external {
        roles[role][account] = enabled;
    }

    function createTestToken(
        address owner,
        uint256 slot,
        uint256 value,
        uint256 maturityDate,
        address issuer
    ) external returns (uint256 tokenId) {
        tokenId = _nextTokenId++;
        tokens[tokenId] = TokenData({
            owner: owner,
            value: value,
            slot: slot,
            issuer: issuer,
            faceValue: value,
            maturityDate: maturityDate,
            status: AssetStatus.Active,
            contractHash: "TEST_HASH",
            metadataURI: "TEST_URI",
            exists: true
        });
    }

    // ========== IAssetCore 实现 ==========

    function hasRole(bytes32 role, address account) external view override returns (bool) {
        return roles[role][account];
    }

    function grantRole(bytes32 role, address account) external override {
        require(roles[DEFAULT_ADMIN_ROLE][msg.sender], "Not admin");
        roles[role][account] = true;
    }

    function revokeRole(bytes32 role, address account) external override {
        require(roles[DEFAULT_ADMIN_ROLE][msg.sender], "Not admin");
        roles[role][account] = false;
    }

    function balanceOf(uint256 tokenId) external view override returns (uint256) {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        return tokens[tokenId].value;
    }

    function ownerOf(uint256 tokenId) external view override returns (address) {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        return tokens[tokenId].owner;
    }

    function slotOf(uint256 tokenId) external view override returns (uint256) {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        return tokens[tokenId].slot;
    }

    // 价值转移：两个已有Token间
    function transferFrom(uint256 fromTokenId, uint256 toTokenId, uint256 value) external override {
        require(tokens[fromTokenId].exists, "RWA: token does not exist");
        require(tokens[toTokenId].exists, "RWA: token does not exist");
        require(tokens[fromTokenId].status == AssetStatus.Active, "RWA: token not active");
        require(value > 0, "RWA: transfer value must be > 0");
        require(tokens[fromTokenId].value >= value, "RWA: insufficient value");
        require(tokens[fromTokenId].slot == tokens[toTokenId].slot, "RWA: slot mismatch");
        require(
            msg.sender == tokens[fromTokenId].owner ||
            _valueApprovals[fromTokenId][msg.sender] >= value ||
            _operatorApprovals[tokens[fromTokenId].owner][msg.sender],
            "RWA: not authorized"
        );

        if (msg.sender != tokens[fromTokenId].owner) {
            uint256 allowed = _valueApprovals[fromTokenId][msg.sender];
            if (allowed != type(uint256).max) {
                _valueApprovals[fromTokenId][msg.sender] = allowed - value;
            }
        }

        tokens[fromTokenId].value -= value;
        tokens[toTokenId].value += value;
    }

    // 价值转移：拆分创建新Token
    function transferFrom(uint256 fromTokenId, address to, uint256 value) external override returns (uint256) {
        require(tokens[fromTokenId].exists, "RWA: token does not exist");
        require(tokens[fromTokenId].status == AssetStatus.Active, "RWA: token not active");
        require(to != address(0), "RWA: transfer to zero address");
        require(value > 0, "RWA: transfer value must be > 0");
        require(tokens[fromTokenId].value >= value, "RWA: insufficient value");
        require(
            msg.sender == tokens[fromTokenId].owner ||
            _valueApprovals[fromTokenId][msg.sender] >= value ||
            _operatorApprovals[tokens[fromTokenId].owner][msg.sender],
            "RWA: not authorized"
        );

        tokens[fromTokenId].value -= value;
        uint256 newTokenId = _nextTokenId++;
        tokens[newTokenId] = TokenData({
            owner: to,
            value: value,
            slot: tokens[fromTokenId].slot,
            issuer: tokens[fromTokenId].issuer,
            faceValue: 0,
            maturityDate: tokens[fromTokenId].maturityDate,
            status: AssetStatus.Active,
            contractHash: tokens[fromTokenId].contractHash,
            metadataURI: tokens[fromTokenId].metadataURI,
            exists: true
        });
        return newTokenId;
    }

    // NFT所有权转移
    function transferFrom(address from, address to, uint256 tokenId) external override {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        require(tokens[tokenId].status == AssetStatus.Active, "RWA: token not active");
        require(from == tokens[tokenId].owner, "RWA: from not owner");
        require(to != address(0), "RWA: transfer to zero address");
        require(
            msg.sender == from ||
            _tokenApprovals[tokenId] == msg.sender ||
            _operatorApprovals[from][msg.sender],
            "RWA: not authorized"
        );

        delete _tokenApprovals[tokenId];
        tokens[tokenId].owner = to;
    }

    function safeTransferFrom(address from, address to, uint256 tokenId) external override {
        this.transferFrom(from, to, tokenId);
    }

    function safeTransferFrom(address from, address to, uint256 tokenId, bytes calldata) external override {
        this.transferFrom(from, to, tokenId);
    }

    // 价值授权
    function approve(uint256 tokenId, address operator, uint256 value) external override {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        require(
            msg.sender == tokens[tokenId].owner ||
            _operatorApprovals[tokens[tokenId].owner][msg.sender],
            "RWA: not authorized"
        );
        _valueApprovals[tokenId][operator] = value;
    }

    function allowance(uint256 tokenId, address operator) external view override returns (uint256) {
        return _valueApprovals[tokenId][operator];
    }

    // NFT授权
    function approve(address to, uint256 tokenId) external override {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        address owner = tokens[tokenId].owner;
        require(
            msg.sender == owner || _operatorApprovals[owner][msg.sender],
            "RWA: not authorized"
        );
        _tokenApprovals[tokenId] = to;
    }

    function getApproved(uint256 tokenId) external view override returns (address) {
        return _tokenApprovals[tokenId];
    }

    function setApprovalForAll(address operator, bool approved) external override {
        require(operator != address(0), "RWA: operator cannot be zero");
        _operatorApprovals[msg.sender][operator] = approved;
    }

    function isApprovedForAll(address owner, address operator) external view override returns (bool) {
        return _operatorApprovals[owner][operator];
    }

    // 资产生命周期
    function mintRWAAsset(
        address to,
        uint256 slot,
        uint256 value,
        uint256 maturityDate,
        string calldata contractHash,
        string calldata uri
    ) external override returns (uint256 tokenId) {
        require(roles[CORE_ENTERPRISE_ROLE][msg.sender], "RWA: not core enterprise");
        tokenId = _nextTokenId++;
        tokens[tokenId] = TokenData({
            owner: to,
            value: value,
            slot: slot,
            issuer: msg.sender,
            faceValue: value,
            maturityDate: maturityDate,
            status: AssetStatus.Active,
            contractHash: contractHash,
            metadataURI: uri,
            exists: true
        });
    }

    function revokeAsset(uint256 tokenId, string calldata) external override {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        require(tokens[tokenId].issuer == msg.sender, "RWA: not the issuer");
        tokens[tokenId].status = AssetStatus.Revoked;
    }

    function settleAsset(uint256 tokenId) external override {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        require(roles[FINANCIAL_INSTITUTION_ROLE][msg.sender], "RWA: not financial institution");
        require(tokens[tokenId].status == AssetStatus.Active, "RWA: asset not active");
        require(block.timestamp >= tokens[tokenId].maturityDate, "RWA: asset not yet mature");
        tokens[tokenId].status = AssetStatus.Settled;
    }

    function setAssetFrozen(uint256 tokenId, bool frozen) external override {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        require(
            roles[FINANCIAL_INSTITUTION_ROLE][msg.sender] || roles[DEFAULT_ADMIN_ROLE][msg.sender],
            "RWA: not authorized"
        );
        tokens[tokenId].status = frozen ? AssetStatus.Frozen : AssetStatus.Active;
    }

    function getAssetInfo(uint256 tokenId)
        external
        view
        override
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
        )
    {
        require(tokens[tokenId].exists, "RWA: token does not exist");
        TokenData storage t = tokens[tokenId];
        return (
            t.slot,
            t.issuer,
            t.faceValue,
            t.maturityDate,
            uint8(t.status),
            t.contractHash,
            t.metadataURI,
            t.value,
            t.owner
        );
    }

    function getHeldAssets(address holder) external view override returns (uint256[] memory) {
        uint256 count;
        for (uint256 i = 1; i < _nextTokenId; i++) {
            if (tokens[i].exists && tokens[i].owner == holder) count++;
        }
        uint256[] memory result = new uint256[](count);
        uint256 idx;
        for (uint256 i = 1; i < _nextTokenId; i++) {
            if (tokens[i].exists && tokens[i].owner == holder) result[idx++] = i;
        }
        return result;
    }

    function getIssuedAssets(address issuer) external view override returns (uint256[] memory) {
        uint256 count;
        for (uint256 i = 1; i < _nextTokenId; i++) {
            if (tokens[i].exists && tokens[i].issuer == issuer) count++;
        }
        uint256[] memory result = new uint256[](count);
        uint256 idx;
        for (uint256 i = 1; i < _nextTokenId; i++) {
            if (tokens[i].exists && tokens[i].issuer == issuer) result[idx++] = i;
        }
        return result;
    }
}
