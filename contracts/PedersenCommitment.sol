// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

/**
 * @title PedersenCommitment
 * @notice Prototype finite-field Pedersen commitments for confidential amounts.
 * @dev The production deployment must use parameters generated for the target
 *      curve/group. This contract keeps only commitments on-chain; openings
 *      (value, blinding) remain off-chain with the authorized business party.
 */
contract PedersenCommitment {
    // 2^255 - 19. The parameters are explicit and replaceable at deployment.
    uint256 public constant MODULUS = 57896044618658097711785492504343953926634992332820282019728792003956564819949;
    uint256 public constant GENERATOR_G = 5;
    uint256 public constant GENERATOR_H = 7;

    mapping(bytes32 assetId => uint256 commitment) public commitments;

    error InvalidValue();
    error InvalidCommitment();
    error SplitVerificationFailed();

    event CommitmentVerified(bytes32 indexed assetId, bool valid);
    event CommitmentStored(bytes32 indexed assetId, uint256 commitment);
    event SplitVerified(bytes32 indexed assetId, uint256 parentCommitment, uint256 child1Commitment, uint256 child2Commitment);

    function commit(uint256 value, uint256 blinding) public pure returns (uint256) {
        if (value >= MODULUS || blinding >= MODULUS) revert InvalidValue();
        return mulmod(_modExp(GENERATOR_G, value), _modExp(GENERATOR_H, blinding), MODULUS);
    }

    function verifyOpening(uint256 commitment, uint256 value, uint256 blinding) public pure returns (bool) {
        if (commitment == 0 || commitment >= MODULUS || value >= MODULUS || blinding >= MODULUS) {
            return false;
        }
        return commitment == commit(value, blinding);
    }

    function storeCommitment(bytes32 assetId, uint256 value, uint256 blinding) external returns (uint256 commitment) {
        require(assetId != bytes32(0), "Pedersen: asset id required");
        require(commitments[assetId] == 0, "Pedersen: commitment already exists");
        commitment = commit(value, blinding);
        commitments[assetId] = commitment;
        emit CommitmentStored(assetId, commitment);
    }

    function verifyStoredSplit(bytes32 parentAssetId, bytes32 child1AssetId, bytes32 child2AssetId)
        external
        returns (bool)
    {
        bool valid = verifySplit(commitments[parentAssetId], commitments[child1AssetId], commitments[child2AssetId]);
        emit CommitmentVerified(parentAssetId, valid);
        if (!valid) revert SplitVerificationFailed();
        return true;
    }

    /**
     * @dev Verifies C_parent = C_child1 * C_child2 (mod p).
     *      The caller must separately ensure the corresponding openings add:
     *      v_parent=v1+v2 and r_parent=r1+r2.
     */
    function verifySplit(uint256 parentCommitment, uint256 child1Commitment, uint256 child2Commitment)
        public
        pure
        returns (bool)
    {
        if (parentCommitment == 0 || child1Commitment == 0 || child2Commitment == 0) return false;
        if (parentCommitment >= MODULUS || child1Commitment >= MODULUS || child2Commitment >= MODULUS) return false;
        return parentCommitment == mulmod(child1Commitment, child2Commitment, MODULUS);
    }

    function verifySplitAndEmit(bytes32 assetId, uint256 parentCommitment, uint256 child1Commitment, uint256 child2Commitment)
        external
        returns (bool)
    {
        bool valid = verifySplit(parentCommitment, child1Commitment, child2Commitment);
        if (!valid) revert SplitVerificationFailed();
        emit SplitVerified(assetId, parentCommitment, child1Commitment, child2Commitment);
        return true;
    }

    function _modExp(uint256 base, uint256 exponent) internal pure returns (uint256 result) {
        result = 1;
        base %= MODULUS;
        while (exponent > 0) {
            if ((exponent & 1) == 1) result = mulmod(result, base, MODULUS);
            base = mulmod(base, base, MODULUS);
            exponent >>= 1;
        }
    }
}
