 // SPDX-License-Identifier: MIT
 pragma solidity ^0.8.26;
 
 /*
  * @title RWA_Core_Asset
  * @notice RWA asset confirmation core contract based on ERC-3525 (SFT)
  * @dev   Implements: asset issuance (core enterprise credit endorsement),
  *        ownership registration, value splitting/merging, asset revocation & status management
  *
  * Role permissions (via OpenZeppelin AccessControl):
  *   DEFAULT_ADMIN_ROLE          - Platform admin
  *   CORE_ENTERPRISE_ROLE        - Core enterprise (issue/revoke own assets)
  *   SUPPLIER_ROLE               - Supplier (receive and transfer assets)
  *   FINANCIAL_INSTITUTION_ROLE  - Financial institution (freeze/verify assets)
  *   AUDITOR_ROLE                - Auditor (read-only access to full data)
  */
 
 import "@openzeppelin/contracts/token/ERC721/IERC721.sol";
 import "@openzeppelin/contracts/token/ERC721/IERC721Receiver.sol";
 import "@openzeppelin/contracts/utils/Context.sol";
 import "@openzeppelin/contracts/utils/Strings.sol";
 import "@openzeppelin/contracts/access/AccessControl.sol";
 import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
 
 contract RWA_Core_Asset is Context, AccessControl, ReentrancyGuard {
     using Strings for uint256;
 
     // ═══════════════════════════════════════════════════════
     //  Constants
     // ═══════════════════════════════════════════════════════
 
     bytes32 public constant CORE_ENTERPRISE_ROLE = keccak256("CORE_ENTERPRISE_ROLE");
     bytes32 public constant SUPPLIER_ROLE        = keccak256("SUPPLIER_ROLE");
     bytes32 public constant FINANCIAL_INSTITUTION_ROLE = keccak256("FINANCIAL_INSTITUTION_ROLE");
     bytes32 public constant AUDITOR_ROLE         = keccak256("AUDITOR_ROLE");
 
     // ═══════════════════════════════════════════════════════
     //  Enums & Structs
     // ═══════════════════════════════════════════════════════
 
     enum AssetStatus {
         Active,   // 0 - Normal active
         Frozen,   // 1 - Frozen (dispute/regulatory hold)
         Revoked,  // 2 - Revoked (core enterprise cancellation)
         Settled   // 3 - Settled (matured & paid off)
     }
 
     struct RWAAssetInfo {
         uint256 slot;                   // Category slot
         address issuer;                 // Issuing core enterprise
         uint256 faceValue;              // Original face value at issuance
         uint256 maturityDate;           // Maturity date (Unix timestamp, seconds)
         AssetStatus status;             // Asset lifecycle status
         string  underlyingContractHash; // keccak256 hash of the underlying procurement contract
         string  metadataURI;            // Off-chain metadata URI (JSON)
     }
 
     // ═══════════════════════════════════════════════════════
     //  State
     // ═══════════════════════════════════════════════════════
 
     string private _name;
     string private _symbol;
 
     // -- ERC-3525 core storage --
     mapping(uint256 => uint256) private _values;
     mapping(uint256 => address) private _owners;
     mapping(uint256 => uint256) private _slots;
     mapping(uint256 => mapping(address => uint256)) private _valueApprovals;
     mapping(address => mapping(address => bool)) private _operatorApprovals;
     mapping(uint256 => address) private _tokenApprovals;
 
     // -- Indexes --
     mapping(address => uint256[]) private _holderTokens;
     mapping(uint256 => uint256) private _holderTokensIndex;
     mapping(address => uint256[]) private _issuerTokens;
     mapping(uint256 => uint256) private _issuerTokensIndex;
     uint256[] private _allTokens;
     mapping(uint256 => uint256) private _allTokensIndex;
 
     // -- RWA extension storage --
     mapping(uint256 => RWAAssetInfo) private _assetInfos;
     uint256 private _nextTokenId;
 
     // ═══════════════════════════════════════════════════════
     //  Events (ERC-3525 standard + RWA extensions)
     // ═══════════════════════════════════════════════════════
 
     event TransferValue(uint256 indexed fromTokenId, uint256 indexed toTokenId, uint256 value);
     event Transfer(address indexed from, address indexed to, uint256 indexed tokenId);
     event ApprovalValue(uint256 indexed tokenId, address indexed operator, uint256 value);
     event ApprovalForAll(address indexed owner, address indexed operator, bool approved);
     event Approval(address indexed owner, address indexed operator, uint256 indexed tokenId);
 
     event AssetCreated(
         uint256 indexed tokenId,
         address indexed issuer,
         uint256 indexed slot,
         uint256 faceValue,
         uint256 maturityDate,
         string  contractHash,
         string  metadataURI
     );
     event AssetSplit(uint256 indexed parentTokenId, uint256 indexed childTokenId, uint256 value);
     event AssetMerged(uint256 indexed tokenId1, uint256 indexed tokenId2, uint256 indexed newTokenId, uint256 value);
     event AssetStatusChanged(uint256 indexed tokenId, AssetStatus oldStatus, AssetStatus newStatus);
     event AssetRevoked(uint256 indexed tokenId, address indexed revokedBy, string reason);
     event AssetSettled(uint256 indexed tokenId, uint256 amount);
 
     // ═══════════════════════════════════════════════════════
     //  Modifiers
     // ═══════════════════════════════════════════════════════
 
     modifier tokenExists(uint256 tokenId) {
         require(_owners[tokenId] != address(0), "RWA: token does not exist");
         _;
     }
 
     modifier onlyActive(uint256 tokenId) {
         require(_assetInfos[tokenId].status == AssetStatus.Active, "RWA: token not active");
         _;
     }
 
     modifier onlyIssuer(uint256 tokenId) {
         require(_msgSender() == _assetInfos[tokenId].issuer, "RWA: not the issuer");
         _;
     }
 
     // ═══════════════════════════════════════════════════════
     //  Constructor
     // ═══════════════════════════════════════════════════════
 
     constructor(string memory name_, string memory symbol_) {
         _name = name_;
         _symbol = symbol_;
         _nextTokenId = 1;
         _grantRole(DEFAULT_ADMIN_ROLE, _msgSender());
     }
 
     // ═══════════════════════════════════════════════════════
     //  Metadata (ERC-3525 / ERC-721 compatible)
     // ═══════════════════════════════════════════════════════
 
     function name() public view returns (string memory) { return _name; }
     function symbol() public view returns (string memory) { return _symbol; }
 
     function tokenURI(uint256 tokenId) public view tokenExists(tokenId) returns (string memory) {
         return _assetInfos[tokenId].metadataURI;
     }
 
     // ═══════════════════════════════════════════════════════
     //  ERC-3525 Core Query
     // ═══════════════════════════════════════════════════════
 
     function balanceOf(uint256 tokenId) public view tokenExists(tokenId) returns (uint256) {
         return _values[tokenId];
     }
 
     function ownerOf(uint256 tokenId) public view tokenExists(tokenId) returns (address) {
         return _owners[tokenId];
     }
 
     function slotOf(uint256 tokenId) public view tokenExists(tokenId) returns (uint256) {
         return _slots[tokenId];
     }
 
     function totalSupply() public view returns (uint256) {
         return _allTokens.length;
     }
 
     function balanceOf(address owner) public view returns (uint256) {
         require(owner != address(0), "RWA: address zero is not a valid owner");
         return _holderTokens[owner].length;
     }
 
     // ═══════════════════════════════════════════════════════
     //  Approval Interfaces
     // ═══════════════════════════════════════════════════════
 
     function approve(
         uint256 tokenId,
         address operator,
         uint256 value
     ) public tokenExists(tokenId) {
         require(
             _msgSender() == _owners[tokenId] || _operatorApprovals[_owners[tokenId]][_msgSender()],
             "RWA: not authorized"
         );
         _valueApprovals[tokenId][operator] = value;
         emit ApprovalValue(tokenId, operator, value);
     }
 
     function allowance(uint256 tokenId, address operator)
         public view tokenExists(tokenId) returns (uint256)
     {
         return _valueApprovals[tokenId][operator];
     }
 
     function setApprovalForAll(address operator, bool approved) public {
         require(operator != address(0), "RWA: operator cannot be zero");
         _operatorApprovals[_msgSender()][operator] = approved;
         emit ApprovalForAll(_msgSender(), operator, approved);
     }
 
     function isApprovedForAll(address owner, address operator) public view returns (bool) {
         return _operatorApprovals[owner][operator];
     }
 
     function approve(address to, uint256 tokenId) public tokenExists(tokenId) {
         address owner = _owners[tokenId];
         require(
             _msgSender() == owner || _operatorApprovals[owner][_msgSender()],
             "RWA: not authorized"
         );
         _tokenApprovals[tokenId] = to;
         emit Approval(owner, to, tokenId);
     }
 
     function getApproved(uint256 tokenId) public view tokenExists(tokenId) returns (address) {
         return _tokenApprovals[tokenId];
     }
 
     // ═══════════════════════════════════════════════════════
     //  ERC-3525 Core Transfer (Value Transfer)
     // ═══════════════════════════════════════════════════════
 
     function transferFrom(
         uint256 fromTokenId,
         address to,
         uint256 value
     ) public virtual nonReentrant tokenExists(fromTokenId) onlyActive(fromTokenId) returns (uint256) {
         require(to != address(0), "RWA: transfer to zero address");
         require(value > 0, "RWA: transfer value must be > 0");
         require(value <= _values[fromTokenId], "RWA: insufficient value");
         require(
             _msgSender() == _owners[fromTokenId] ||
             _valueApprovals[fromTokenId][_msgSender()] >= value ||
             isApprovedForAll(_owners[fromTokenId], _msgSender()),
             "RWA: not authorized"
         );
 
         if (_msgSender() != _owners[fromTokenId]) {
             uint256 allowed = _valueApprovals[fromTokenId][_msgSender()];
             if (allowed != type(uint256).max) {
                 _valueApprovals[fromTokenId][_msgSender()] = allowed - value;
             }
         }
 
         _values[fromTokenId] -= value;
 
         uint256 newTokenId = _nextTokenId++;
         _values[newTokenId] = value;
         _owners[newTokenId] = to;
         _slots[newTokenId] = _slots[fromTokenId];
 
         RWAAssetInfo storage parentInfo = _assetInfos[fromTokenId];
         _assetInfos[newTokenId] = RWAAssetInfo({
             slot: parentInfo.slot,
             issuer: parentInfo.issuer,
             faceValue: 0,
             maturityDate: parentInfo.maturityDate,
             status: AssetStatus.Active,
             underlyingContractHash: parentInfo.underlyingContractHash,
             metadataURI: parentInfo.metadataURI
         });
 
         _addTokenToOwnerEnum(to, newTokenId);
         _addTokenToAllTokensEnum(newTokenId);
 
         emit TransferValue(fromTokenId, newTokenId, value);
         emit Transfer(address(0), to, newTokenId);
         emit AssetSplit(fromTokenId, newTokenId, value);
 
         return newTokenId;
     }
 
     function transferFrom(
         uint256 fromTokenId,
         uint256 toTokenId,
         uint256 value
     ) public virtual nonReentrant tokenExists(fromTokenId) tokenExists(toTokenId) onlyActive(fromTokenId) {
         require(value > 0, "RWA: transfer value must be > 0");
         require(value <= _values[fromTokenId], "RWA: insufficient value");
         require(_slots[fromTokenId] == _slots[toTokenId], "RWA: slot mismatch");
         require(
             _msgSender() == _owners[fromTokenId] ||
             _valueApprovals[fromTokenId][_msgSender()] >= value ||
             isApprovedForAll(_owners[fromTokenId], _msgSender()),
             "RWA: not authorized"
         );
 
         if (_msgSender() != _owners[fromTokenId]) {
             uint256 allowed = _valueApprovals[fromTokenId][_msgSender()];
             if (allowed != type(uint256).max) {
                 _valueApprovals[fromTokenId][_msgSender()] = allowed - value;
             }
         }
 
         _values[fromTokenId] -= value;
         _values[toTokenId] += value;
 
         emit TransferValue(fromTokenId, toTokenId, value);
     }
 
     function transferFrom(
         address from,
         address to,
         uint256 tokenId
     ) public virtual nonReentrant tokenExists(tokenId) onlyActive(tokenId) {
         require(from == _owners[tokenId], "RWA: from not owner");
         require(to != address(0), "RWA: transfer to zero address");
         require(
             _msgSender() == from ||
             _tokenApprovals[tokenId] == _msgSender() ||
             _operatorApprovals[from][_msgSender()],
             "RWA: not authorized"
         );
 
         delete _tokenApprovals[tokenId];
         _removeTokenFromOwnerEnum(from, tokenId);
         _owners[tokenId] = to;
         _addTokenToOwnerEnum(to, tokenId);
 
         emit Transfer(from, to, tokenId);
     }
 
     function safeTransferFrom(address from, address to, uint256 tokenId) public {
         transferFrom(from, to, tokenId);
         _checkOnERC721Received(from, to, tokenId, "");
     }
 
     function safeTransferFrom(address from, address to, uint256 tokenId, bytes memory data) public {
         transferFrom(from, to, tokenId);
         _checkOnERC721Received(from, to, tokenId, data);
     }
 
     // ═══════════════════════════════════════════════════════
     //  RWA Extension - Asset Issuance (Core Enterprise Credit Endorsement)
     // ═══════════════════════════════════════════════════════
 
     function mintRWAAsset(
         address to,
         uint256 slot,
         uint256 value,
         uint256 maturityDate,
         string calldata contractHash,
         string calldata uri
     ) external onlyRole(CORE_ENTERPRISE_ROLE) returns (uint256 tokenId) {
         require(to != address(0), "RWA: mint to zero address");
         require(value > 0, "RWA: mint value must be > 0");
         require(maturityDate > block.timestamp, "RWA: maturity must be in future");
         require(bytes(contractHash).length > 0, "RWA: contract hash required");
 
         tokenId = _nextTokenId++;
         address issuer = _msgSender();
 
         _values[tokenId] = value;
         _owners[tokenId] = to;
         _slots[tokenId] = slot;
 
         _assetInfos[tokenId] = RWAAssetInfo({
             slot: slot,
             issuer: issuer,
             faceValue: value,
             maturityDate: maturityDate,
             status: AssetStatus.Active,
             underlyingContractHash: contractHash,
             metadataURI: uri
         });
 
         _addTokenToOwnerEnum(to, tokenId);
         _addTokenToIssuerEnum(issuer, tokenId);
         _addTokenToAllTokensEnum(tokenId);
 
         emit Transfer(address(0), to, tokenId);
         emit AssetCreated(tokenId, issuer, slot, value, maturityDate, contractHash, uri);
 
         return tokenId;
     }
 
     function revokeAsset(uint256 tokenId, string calldata reason)
         external tokenExists(tokenId) onlyIssuer(tokenId)
     {
         RWAAssetInfo storage info = _assetInfos[tokenId];
         require(info.status == AssetStatus.Active, "RWA: asset not active");
 
         AssetStatus oldStatus = info.status;
         info.status = AssetStatus.Revoked;
 
         emit AssetStatusChanged(tokenId, oldStatus, AssetStatus.Revoked);
         emit AssetRevoked(tokenId, _msgSender(), reason);
     }
 
     function settleAsset(uint256 tokenId)
         external tokenExists(tokenId) onlyRole(FINANCIAL_INSTITUTION_ROLE)
     {
         RWAAssetInfo storage info = _assetInfos[tokenId];
         require(info.status == AssetStatus.Active, "RWA: asset not active");
         require(block.timestamp >= info.maturityDate, "RWA: asset not yet mature");
 
         AssetStatus oldStatus = info.status;
         info.status = AssetStatus.Settled;
 
         emit AssetStatusChanged(tokenId, oldStatus, AssetStatus.Settled);
         emit AssetSettled(tokenId, _values[tokenId]);
     }
 
     function setAssetFrozen(uint256 tokenId, bool frozen)
         external tokenExists(tokenId)
     {
         require(
             hasRole(FINANCIAL_INSTITUTION_ROLE, _msgSender()) ||
             hasRole(DEFAULT_ADMIN_ROLE, _msgSender()),
             "RWA: not financial institution or admin"
         );
 
         RWAAssetInfo storage info = _assetInfos[tokenId];
         require(info.status == AssetStatus.Active || info.status == AssetStatus.Frozen,
             "RWA: asset cannot be frozen in current state");
 
         AssetStatus oldStatus = info.status;
         info.status = frozen ? AssetStatus.Frozen : AssetStatus.Active;
 
         emit AssetStatusChanged(tokenId, oldStatus, info.status);
     }
 
     // ═══════════════════════════════════════════════════════
     //  RWA Query Interfaces
     // ═══════════════════════════════════════════════════════
 
     function getAssetInfo(uint256 tokenId)
         external view tokenExists(tokenId)
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
         RWAAssetInfo storage info = _assetInfos[tokenId];
         return (
             info.slot,
             info.issuer,
             info.faceValue,
             info.maturityDate,
             uint8(info.status),
             info.underlyingContractHash,
             info.metadataURI,
             _values[tokenId],
             _owners[tokenId]
         );
     }
 
     function getIssuedAssets(address issuer) external view returns (uint256[] memory) {
         return _issuerTokens[issuer];
     }
 
     function getHeldAssets(address holder) external view returns (uint256[] memory) {
         return _holderTokens[holder];
     }
 
     function getAssetsBySlot(uint256 slot) external view returns (uint256[] memory) {
         uint256 total = _allTokens.length;
         uint256 count;
         for (uint256 i; i < total; ++i) {
             if (_slots[_allTokens[i]] == slot) ++count;
         }
         uint256[] memory result = new uint256[](count);
         uint256 idx;
         for (uint256 i; i < total; ++i) {
             if (_slots[_allTokens[i]] == slot) result[idx++] = _allTokens[i];
         }
         return result;
     }
 
     function computeSlot(address issuer, uint256 maturityDate, uint256 category)
         external pure returns (uint256)
     {
         return uint256(keccak256(abi.encodePacked(issuer, maturityDate, category)));
     }
 
     // ═══════════════════════════════════════════════════════
     //  Internal Helpers
     // ═══════════════════════════════════════════════════════
 
     function _addTokenToOwnerEnum(address to, uint256 tokenId) private {
         _holderTokensIndex[tokenId] = _holderTokens[to].length;
         _holderTokens[to].push(tokenId);
     }
 
     function _removeTokenFromOwnerEnum(address from, uint256 tokenId) private {
         uint256 lastTokenIndex = _holderTokens[from].length - 1;
         uint256 tokenIndex = _holderTokensIndex[tokenId];
         if (tokenIndex != lastTokenIndex) {
             uint256 lastTokenId = _holderTokens[from][lastTokenIndex];
             _holderTokens[from][tokenIndex] = lastTokenId;
             _holderTokensIndex[lastTokenId] = tokenIndex;
         }
         _holderTokens[from].pop();
         delete _holderTokensIndex[tokenId];
     }
 
     function _addTokenToIssuerEnum(address issuer, uint256 tokenId) private {
         _issuerTokensIndex[tokenId] = _issuerTokens[issuer].length;
         _issuerTokens[issuer].push(tokenId);
     }
 
     function _addTokenToAllTokensEnum(uint256 tokenId) private {
         _allTokensIndex[tokenId] = _allTokens.length;
         _allTokens.push(tokenId);
     }
 
     function _checkOnERC721Received(address from, address to, uint256 tokenId, bytes memory data) private {
         if (to.code.length > 0) {
             try IERC721Receiver(to).onERC721Received(_msgSender(), from, tokenId, data)
                 returns (bytes4 retval) {
                 require(retval == IERC721Receiver.onERC721Received.selector,
                     "RWA: transfer to non ERC721Receiver");
             } catch (bytes memory) {
                 revert("RWA: transfer to non ERC721Receiver");
             }
         }
     }
 
     // ═══════════════════════════════════════════════════════
     //  ERC-165 Support
     // ═══════════════════════════════════════════════════════
 
     function supportsInterface(bytes4 interfaceId)
         public view virtual override returns (bool)
     {
         return
             interfaceId == type(IERC721).interfaceId ||
             super.supportsInterface(interfaceId);
     }
 }
