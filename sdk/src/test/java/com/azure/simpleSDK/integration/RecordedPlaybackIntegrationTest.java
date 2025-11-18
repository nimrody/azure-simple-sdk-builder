package com.azure.simpleSDK.integration;

import com.azure.simpleSDK.authorization.client.AzureAuthorizationClient;
import com.azure.simpleSDK.authorization.models.RoleAssignmentListResult;
import com.azure.simpleSDK.compute.client.AzureComputeClient;
import com.azure.simpleSDK.compute.models.VirtualMachineListResult;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSetListWithLinkResult;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSetVMListResult;
import com.azure.simpleSDK.http.AzureHttpClient;
import com.azure.simpleSDK.http.AzureResponse;
import com.azure.simpleSDK.http.auth.AzureCredentials;
import com.azure.simpleSDK.http.exceptions.AzureAuthenticationException;
import com.azure.simpleSDK.http.exceptions.AzureException;
import com.azure.simpleSDK.http.exceptions.AzureResourceNotFoundException;
import com.azure.simpleSDK.http.recording.HttpInteractionRecorder;
import com.azure.simpleSDK.network.client.AzureNetworkClient;
import com.azure.simpleSDK.network.models.AzureFirewallListResult;
import com.azure.simpleSDK.network.models.NetworkInterfaceListResult;
import com.azure.simpleSDK.network.models.NetworkSecurityGroupListResult;
import com.azure.simpleSDK.network.models.PublicIPAddress;
import com.azure.simpleSDK.network.models.VirtualNetworkListResult;
import com.azure.simpleSDK.resources.client.AzureResourcesClient;
import com.azure.simpleSDK.resources.models.SubscriptionListResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end regression test that replays a recorded demo session to verify that the SDK
 * can deserialize and serve data across multiple Azure services without issuing live HTTP calls.
 */
class RecordedPlaybackIntegrationTest {
    private static final String SUBSCRIPTION_ID = "40516be1-2840-44df-9333-c39247d77429";
    private static final String US_EAST_RG = "US-EAST-RESOURCE-GROUP-1";
    private static final String EU_WEST_RG = "EU-WEST-RESOURCE-GROUP-1";
    private static final String VMSS_US_EAST = "vm-ss-1-us-east";
    private static final String VMSS_EU_WEST = "vm-ss-1-eu-west";
    private static final String VMSS_NIC = "vnet-1-us-east-nic01";
    private static final String VMSS_IP_CONFIG = "vnet-1-us-east-nic01-defaultIpConfiguration";
    private static final String VMSS_PUBLIC_IP = "publicIp-vnet-1-us-east-nic01";
    private static final String PRINCIPAL_ID = "319e61e7-176f-48b5-b91e-b3ee7f446c9e";

    @Test
    void replayRecordedSessionAcrossServices() throws Exception {
        Path recordings = resolveRecordingDirectory();
        HttpInteractionRecorder recorder =
            new HttpInteractionRecorder(HttpInteractionRecorder.Mode.PLAYBACK, recordings);
        AzureHttpClient.setGlobalRecorder(recorder);

        AzureCredentials credentials = new PlaybackCredentials();
        AzureResourcesClient resourcesClient = new AzureResourcesClient(credentials, false);
        AzureNetworkClient networkClient = new AzureNetworkClient(credentials, false);
        AzureComputeClient computeClient = new AzureComputeClient(credentials, false);
        AzureAuthorizationClient authorizationClient = new AzureAuthorizationClient(credentials, false);

        try {
            AzureResponse<SubscriptionListResult> subscriptions = resourcesClient.listSubscriptions();
            assertEquals(200, subscriptions.getStatusCode());
            assertNotNull(subscriptions.getBody());
            assertEquals(4, subscriptions.getBody().value().size());
            assertEquals("te-azr-cii-staging", subscriptions.getBody().value().get(0).displayName());

            AzureResponse<AzureFirewallListResult> firewalls =
                networkClient.listAllAzureFirewalls(SUBSCRIPTION_ID);
            assertNotNull(firewalls.getBody());
            assertTrue(firewalls.getBody().value().isEmpty(), "Expected zero firewalls in fixture");

            AzureResponse<VirtualNetworkListResult> virtualNetworks =
                networkClient.listAllVirtualNetworks(SUBSCRIPTION_ID);
            assertEquals(6, virtualNetworks.getBody().value().size());
            assertEquals("vnet-1-us-west", virtualNetworks.getBody().value().get(0).name());

            AzureResponse<NetworkInterfaceListResult> networkInterfaces =
                networkClient.listAllNetworkInterfaces(SUBSCRIPTION_ID);
            assertEquals(20, networkInterfaces.getBody().value().size());
            assertEquals("ubuntu-azure-08564", networkInterfaces.getBody().value().get(0).name());

            AzureResponse<NetworkSecurityGroupListResult> securityGroups =
                networkClient.listAllNetworkSecurityGroups(SUBSCRIPTION_ID);
            assertEquals(15, securityGroups.getBody().value().size());
            assertEquals("nsg-01", securityGroups.getBody().value().get(0).name());

            assertThrows(
                AzureResourceNotFoundException.class,
                () -> networkClient.getNetworkSecurityGroups(SUBSCRIPTION_ID, "nsg-neu-rg", "shared-services-neu-nsg", null));

            assertThrows(
                AzureResourceNotFoundException.class,
                () -> networkClient.getNetworkSecurityGroups(SUBSCRIPTION_ID, "nsg-zzz", "xxxx", null));

            AzureResponse<VirtualMachineListResult> eastUsVms =
                computeClient.listByLocationVirtualMachines(SUBSCRIPTION_ID, "eastus");
            assertEquals(9, eastUsVms.getBody().value().size());
            assertEquals("te-agent-01", eastUsVms.getBody().value().get(0).name());

            AzureResponse<VirtualMachineScaleSetListWithLinkResult> vmScaleSets =
                computeClient.listAllVirtualMachineScaleSets(SUBSCRIPTION_ID);
            assertEquals(2, vmScaleSets.getBody().value().size());
            assertEquals(VMSS_US_EAST, vmScaleSets.getBody().value().get(0).name());

            AzureResponse<NetworkInterfaceListResult> usEastVmssNics =
                networkClient.listVirtualMachineScaleSetNetworkInterfacesNetworkInterfaces(
                    SUBSCRIPTION_ID, US_EAST_RG, VMSS_US_EAST);
            assertEquals(2, usEastVmssNics.getBody().value().size());
            assertEquals("vnet-1-us-east-nic01", usEastVmssNics.getBody().value().get(0).name());

            AzureResponse<VirtualMachineScaleSetVMListResult> usEastVmssVms =
                computeClient.listVirtualMachineScaleSetVMs(
                    SUBSCRIPTION_ID, US_EAST_RG, VMSS_US_EAST, null, null, null);
            assertEquals(2, usEastVmssVms.getBody().value().size());
            assertEquals("vm-ss-1-us-east_0", usEastVmssVms.getBody().value().get(0).name());

            AzureResponse<PublicIPAddress> usEastPublicIpNode0 =
                networkClient.getVirtualMachineScaleSetPublicIPAddressPublicIPAddresses(
                    SUBSCRIPTION_ID, US_EAST_RG, VMSS_US_EAST, "0",
                    VMSS_NIC, VMSS_IP_CONFIG, VMSS_PUBLIC_IP, null);
            assertEquals("20.106.172.87", usEastPublicIpNode0.getBody().properties().ipAddress());

            AzureResponse<PublicIPAddress> usEastPublicIpNode1 =
                networkClient.getVirtualMachineScaleSetPublicIPAddressPublicIPAddresses(
                    SUBSCRIPTION_ID, US_EAST_RG, VMSS_US_EAST, "1",
                    VMSS_NIC, VMSS_IP_CONFIG, VMSS_PUBLIC_IP, null);
            assertEquals("172.212.104.107", usEastPublicIpNode1.getBody().properties().ipAddress());

            AzureResponse<NetworkInterfaceListResult> euWestVmssNics =
                networkClient.listVirtualMachineScaleSetNetworkInterfacesNetworkInterfaces(
                    SUBSCRIPTION_ID, EU_WEST_RG, VMSS_EU_WEST);
            assertEquals(2, euWestVmssNics.getBody().value().size());
            assertEquals("vnet-1-eu-west-nic01", euWestVmssNics.getBody().value().get(0).name());

            AzureResponse<VirtualMachineScaleSetVMListResult> euWestVmssVms =
                computeClient.listVirtualMachineScaleSetVMs(
                    SUBSCRIPTION_ID, EU_WEST_RG, VMSS_EU_WEST, null, null, null);
            assertEquals(2, euWestVmssVms.getBody().value().size());
            assertEquals("vm-ss-1-eu-west_0", euWestVmssVms.getBody().value().get(0).name());

            AzureResponse<RoleAssignmentListResult> roleAssignments =
                authorizationClient.listForSubscriptionRoleAssignments(
                    SUBSCRIPTION_ID, "principalId eq '" + PRINCIPAL_ID + "'", null);
            assertNotNull(roleAssignments.getBody());
            assertTrue(roleAssignments.getBody().value().isEmpty(), "Regression data records zero assignments");
        } finally {
            AzureHttpClient.setGlobalRecorder(null);
        }
    }

    private static Path resolveRecordingDirectory() {
        Path[] candidates = new Path[] {
            Paths.get("src", "test", "resources", "recordings", "record-78abb1c"),
            Paths.get("..", "sdk", "src", "test", "resources", "recordings", "record-78abb1c"),
            Paths.get("..", "src", "test", "resources", "recordings", "record-78abb1c")
        };

        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                return absolute;
            }
        }

        fail("Recorded fixtures missing. Expected to find 'record-78abb1c' under sdk/src/test/resources/recordings.");
        return null;
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
            // no-op for playback
        }
    }
}
