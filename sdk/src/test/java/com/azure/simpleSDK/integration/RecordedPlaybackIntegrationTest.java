package com.azure.simpleSDK.integration;

import com.azure.simpleSDK.authorization.client.AzureAuthorizationClient;
import com.azure.simpleSDK.authorization.models.RoleAssignmentListResult;
import com.azure.simpleSDK.compute.client.AzureComputeClient;
import com.azure.simpleSDK.compute.models.VirtualMachineListResult;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSet;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSetListWithLinkResult;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSetVMListResult;
import com.azure.simpleSDK.http.AzureHttpClient;
import com.azure.simpleSDK.http.AzureResponse;
import com.azure.simpleSDK.http.auth.AzureCredentials;
import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;
import com.azure.simpleSDK.http.exceptions.AzureException;
import com.azure.simpleSDK.http.exceptions.AzureResourceNotFoundException;
import com.azure.simpleSDK.http.exceptions.AzureServiceException;
import com.azure.simpleSDK.http.recording.HttpInteractionRecorder;
import com.azure.simpleSDK.network.client.AzureNetworkClient;
import com.azure.simpleSDK.network.models.AzureFirewallListResult;
import com.azure.simpleSDK.network.models.NetworkInterfaceListResult;
import com.azure.simpleSDK.network.models.NetworkSecurityGroupListResult;
import com.azure.simpleSDK.network.models.PublicIPAddress;
import com.azure.simpleSDK.network.models.VirtualNetworkListResult;
import com.azure.simpleSDK.resources.client.AzureResourcesClient;
import com.azure.simpleSDK.resources.models.SubscriptionListResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays the canned recordings captured by the demo application and compares the SDK output
 * against the expected JSON references produced during recording.
 */
class RecordedPlaybackIntegrationTest {
    private static final String SUBSCRIPTION_ID = "40516be1-2840-44df-9333-c39247d77429";
    private static final String PRINCIPAL_ID = "319e61e7-176f-48b5-b91e-b3ee7f446c9e";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final boolean UPDATE_EXPECTATIONS =
        Boolean.getBoolean("updateExpectations") ||
            "true".equalsIgnoreCase(System.getenv("UPDATE_EXPECTATIONS"));

    @Test
    void replayRecordedSessionAcrossServices() throws Exception {
        Path recordings = resolveDirectory("src/test/resources/recordings/record-78abb1c");
        Path expectations = resolveDirectory("src/test/resources/expected/record-78abb1c");

        HttpInteractionRecorder recorder =
            new HttpInteractionRecorder(HttpInteractionRecorder.Mode.PLAYBACK, recordings);
        AzureHttpClient.setGlobalRecorder(recorder);

        AzureCredentials credentials = new PlaybackCredentials();
        AzureResourcesClient resourcesClient = new AzureResourcesClient(credentials, false);
        AzureNetworkClient networkClient = new AzureNetworkClient(credentials, false);
        AzureComputeClient computeClient = new AzureComputeClient(credentials, false);
        AzureAuthorizationClient authorizationClient = new AzureAuthorizationClient(credentials, false);

        try {
            assertResponseMatches(expectations, "resources_listSubscriptions",
                resourcesClient.listSubscriptions());

            assertResponseMatches(expectations, "network_listAllAzureFirewalls",
                networkClient.listAllAzureFirewalls(SUBSCRIPTION_ID));

            assertResponseMatches(expectations, "network_listAllVirtualNetworks",
                networkClient.listAllVirtualNetworks(SUBSCRIPTION_ID));

            assertResponseMatches(expectations, "network_listAllNetworkInterfaces",
                networkClient.listAllNetworkInterfaces(SUBSCRIPTION_ID));

            assertResponseMatches(expectations, "network_listAllNetworkSecurityGroups",
                networkClient.listAllNetworkSecurityGroups(SUBSCRIPTION_ID));

            assertNotFound(expectations, "network_getNetworkSecurityGroup_nsg-neu-rg_shared-services-neu-nsg",
                () -> networkClient.getNetworkSecurityGroups(SUBSCRIPTION_ID, "nsg-neu-rg", "shared-services-neu-nsg", null));

            assertNotFound(expectations, "network_getNetworkSecurityGroup_nsg-zzz_xxxx",
                () -> networkClient.getNetworkSecurityGroups(SUBSCRIPTION_ID, "nsg-zzz", "xxxx", null));

            assertResponseMatches(expectations, "compute_listVirtualMachines_eastus",
                computeClient.listByLocationVirtualMachines(SUBSCRIPTION_ID, "eastus"));

            AzureResponse<VirtualMachineScaleSetListWithLinkResult> vmssResponse =
                computeClient.listAllVirtualMachineScaleSets(SUBSCRIPTION_ID);
            assertResponseMatches(expectations, "compute_listAllVirtualMachineScaleSets", vmssResponse);

            if (vmssResponse.getBody() != null && vmssResponse.getBody().value() != null) {
                for (VirtualMachineScaleSet vmss : vmssResponse.getBody().value()) {
                    if (vmss == null) {
                        continue;
                    }
                    String vmssName = vmss.name();
                    String sanitized = sanitizeName(vmssName);
                    String resourceGroup = extractResourceGroupFromId(vmss.id());

                    assertResponseMatches(expectations,
                        "network_vmss_" + sanitized + "_networkInterfaces",
                        networkClient.listVirtualMachineScaleSetNetworkInterfacesNetworkInterfaces(
                            SUBSCRIPTION_ID, resourceGroup, vmssName));

                    assertResponseMatches(expectations,
                        "compute_vmss_" + sanitized + "_virtualMachines",
                        computeClient.listVirtualMachineScaleSetVMs(
                            SUBSCRIPTION_ID, resourceGroup, vmssName, null, null, null));
                }
            }

            // Specific public IP lookups captured during recording
            assertResponseMatches(expectations,
                "network_vmss_vm-ss-1-us-east_instance0_publicIp",
                networkClient.getVirtualMachineScaleSetPublicIPAddressPublicIPAddresses(
                    SUBSCRIPTION_ID, "US-EAST-RESOURCE-GROUP-1", "vm-ss-1-us-east", "0",
                    "vnet-1-us-east-nic01", "vnet-1-us-east-nic01-defaultIpConfiguration", "publicIp-vnet-1-us-east-nic01", null));

            assertResponseMatches(expectations,
                "network_vmss_vm-ss-1-us-east_instance1_publicIp",
                networkClient.getVirtualMachineScaleSetPublicIPAddressPublicIPAddresses(
                    SUBSCRIPTION_ID, "US-EAST-RESOURCE-GROUP-1", "vm-ss-1-us-east", "1",
                    "vnet-1-us-east-nic01", "vnet-1-us-east-nic01-defaultIpConfiguration", "publicIp-vnet-1-us-east-nic01", null));

            String scope = "subscriptions/" + SUBSCRIPTION_ID;
            String filter = "principalId eq '" + PRINCIPAL_ID + "'";
            assertResponseMatches(expectations,
                "authorization_listRoleAssignments_" + sanitizeName(scope),
                authorizationClient.listForScopeRoleAssignments(scope, filter, null, null));
        } finally {
            AzureHttpClient.setGlobalRecorder(null);
        }
    }

    private static void assertResponseMatches(Path expectedDir, String key, AzureResponse<?> response) throws IOException {
        JsonNode actual = canonicalize(OBJECT_MAPPER.valueToTree(
            new RecordedResponseEnvelope(response.getStatusCode(), response.getBody())));
        compareOrUpdate(expectedDir, key, actual);
    }

    private static void assertNotFound(Path expectedDir, String key, Executable executable) throws Exception {
        AzureResourceNotFoundException exception = assertThrows(
            AzureResourceNotFoundException.class,
            executable,
            "Expected AzureResourceNotFoundException for " + key);
        JsonNode actual = canonicalize(OBJECT_MAPPER.valueToTree(
            new RecordedErrorEnvelope(
                exception.getClass().getSimpleName(),
                exception.getStatusCode(),
                exception.getMessage(),
                exception.getErrorCode(),
                exception.getResponseBody()
            )
        ));
        compareOrUpdate(expectedDir, key, actual);
    }

    private static void compareOrUpdate(Path expectedDir, String key, JsonNode actual) throws IOException {
        if (UPDATE_EXPECTATIONS) {
            writeExpected(expectedDir, key, actual);
            return;
        }
        JsonNode expected = canonicalize(readExpected(expectedDir, key));
        if (!expected.equals(actual)) {
            System.err.println("Expected (" + key + "): " + expected.toPrettyString());
            System.err.println("Actual   (" + key + "): " + actual.toPrettyString());
            debugDifference(expected, actual, key);
        }
        assertEquals(expected, actual, "Mismatch for " + key);
    }

    private static JsonNode readExpected(Path expectedDir, String key) throws IOException {
        Path file = expectedDir.resolve(key + ".json");
        assertTrue(Files.exists(file), "Missing expected output for " + key + " at " + file);
        return OBJECT_MAPPER.readTree(file.toFile());
    }

    private static void writeExpected(Path expectedDir, String key, JsonNode actual) throws IOException {
        Path file = expectedDir.resolve(key + ".json");
        Files.createDirectories(file.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), actual);
    }

    private static JsonNode canonicalize(JsonNode node) throws IOException {
        if (node == null) {
            return null;
        }
        return OBJECT_MAPPER.readTree(node.toString());
    }

    private static void debugDifference(JsonNode expected, JsonNode actual, String path) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            System.err.println("Difference at " + path + ": one node is null");
            return;
        }
        if (expected.equals(actual)) {
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            var fieldNames = expected.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                debugDifference(expected.get(field), actual.get(field), path + "." + field);
            }
            actual.fieldNames().forEachRemaining(field -> {
                if (!expected.has(field)) {
                    System.err.println("Difference at " + path + ": unexpected field '" + field + "'");
                }
            });
        } else if (expected.isArray() && actual.isArray()) {
            int size = Math.min(expected.size(), actual.size());
            for (int i = 0; i < size; i++) {
                debugDifference(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
            if (expected.size() != actual.size()) {
                System.err.println("Difference at " + path + ": array length " + expected.size() + " vs " + actual.size());
            }
        } else {
            System.err.println("Difference at " + path + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static Path resolveDirectory(String primary) {
        Path candidate = Paths.get(primary).toAbsolutePath();
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        candidate = Paths.get("sdk", primary).toAbsolutePath();
        assertTrue(Files.isDirectory(candidate), "Could not locate directory: " + primary);
        return candidate;
    }

    private static String extractResourceGroupFromId(String resourceId) {
        if (resourceId == null) {
            return "";
        }
        String[] parts = resourceId.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("resourceGroups".equalsIgnoreCase(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private static String sanitizeName(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        return input.replaceAll("[^a-zA-Z0-9-_]", "-");
    }

    private static final class PlaybackCredentials implements AzureCredentials {
        @Override
        public String getAccessToken() throws AzureAuthenticationException {
            return "recorded-token";
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public void refresh() throws AzureAuthenticationException {
            // no-op
        }
    }

    private record RecordedResponseEnvelope(int statusCode, Object body) { }

    private record RecordedErrorEnvelope(String errorType, int statusCode, String message, String errorCode, String responseBody) { }
}
