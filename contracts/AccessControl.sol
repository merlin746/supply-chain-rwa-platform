// SPDX-License-Identifier: MIT
pragma solidity ^0.8.26;

import {AccessControl as OZAccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";

/**
 * @title AccessControl
 * @notice Four-role RBAC manager for business-sensitive RWA data.
 * @dev Field permissions gate contract/API calls. Sensitive plaintext must
 *      remain off-chain and encrypted; RBAC alone cannot hide public chain state.
 */
contract AccessControl is OZAccessControl {
    bytes32 public constant ADMIN_ROLE = keccak256("ADMIN_ROLE");
    bytes32 public constant CORE_ENTERPRISE_ROLE = keccak256("CORE_ENTERPRISE_ROLE");
    bytes32 public constant SUPPLIER_ROLE = keccak256("SUPPLIER_ROLE");
    bytes32 public constant BANK_ROLE = keccak256("BANK_ROLE");

    mapping(bytes32 resourceId => mapping(bytes32 fieldId => mapping(bytes32 role => bool)))
        private _fieldRolePermissions;

    event FieldPermissionUpdated(bytes32 indexed resourceId, bytes32 indexed fieldId, bytes32 indexed role, bool allowed);

    constructor() {
        _grantRole(DEFAULT_ADMIN_ROLE, msg.sender);
        _grantRole(ADMIN_ROLE, msg.sender);
        _setRoleAdmin(ADMIN_ROLE, ADMIN_ROLE);
        _setRoleAdmin(CORE_ENTERPRISE_ROLE, ADMIN_ROLE);
        _setRoleAdmin(SUPPLIER_ROLE, ADMIN_ROLE);
        _setRoleAdmin(BANK_ROLE, ADMIN_ROLE);
    }

    function setFieldRolePermission(bytes32 resourceId, bytes32 fieldId, bytes32 role, bool allowed)
        external
        onlyRole(ADMIN_ROLE)
    {
        require(
            role == ADMIN_ROLE || role == CORE_ENTERPRISE_ROLE || role == SUPPLIER_ROLE || role == BANK_ROLE,
            "AccessControl: unsupported role"
        );
        _fieldRolePermissions[resourceId][fieldId][role] = allowed;
        emit FieldPermissionUpdated(resourceId, fieldId, role, allowed);
    }

    function canReadField(bytes32 resourceId, bytes32 fieldId, address account) public view returns (bool) {
        return hasRole(ADMIN_ROLE, account)
            || _fieldRolePermissions[resourceId][fieldId][ADMIN_ROLE] && hasRole(ADMIN_ROLE, account)
            || _fieldRolePermissions[resourceId][fieldId][CORE_ENTERPRISE_ROLE] && hasRole(CORE_ENTERPRISE_ROLE, account)
            || _fieldRolePermissions[resourceId][fieldId][SUPPLIER_ROLE] && hasRole(SUPPLIER_ROLE, account)
            || _fieldRolePermissions[resourceId][fieldId][BANK_ROLE] && hasRole(BANK_ROLE, account);
    }

    function requireCanReadField(bytes32 resourceId, bytes32 fieldId) external view {
        require(canReadField(resourceId, fieldId, msg.sender), "AccessControl: field read denied");
    }

    function fieldRolePermission(bytes32 resourceId, bytes32 fieldId, bytes32 role) external view returns (bool) {
        return _fieldRolePermissions[resourceId][fieldId][role];
    }
}
