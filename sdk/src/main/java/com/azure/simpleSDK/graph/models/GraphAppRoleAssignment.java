package com.azure.simpleSDK.graph.models;

/**
 * Minimal view of a Microsoft Graph app role assignment.
 */
public record GraphAppRoleAssignment(
        String id,
        String resourceId,
        String resourceDisplayName,
        String appRoleId,
        String principalId,
        String principalDisplayName) {
}
