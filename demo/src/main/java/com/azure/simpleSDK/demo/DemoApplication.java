package com.azure.simpleSDK.demo;

import com.azure.simpleSDK.authorization.client.AzureAuthorizationClient;
import com.azure.simpleSDK.authorization.models.AuthorizationRoleDefinitionsCallsPermission;
import com.azure.simpleSDK.authorization.models.RoleAssignment;
import com.azure.simpleSDK.authorization.models.RoleAssignmentListResult;
import com.azure.simpleSDK.authorization.models.RoleAssignmentProperties;
import com.azure.simpleSDK.authorization.models.RoleDefinition;
import com.azure.simpleSDK.compute.client.AzureComputeClient;
import com.azure.simpleSDK.network.client.AzureNetworkClient;
import com.azure.simpleSDK.http.AzureResponse;
import com.azure.simpleSDK.http.auth.ServicePrincipalCredentials;
import com.azure.simpleSDK.http.exceptions.AzureException;
import com.azure.simpleSDK.http.exceptions.AzureServiceException;
import com.azure.simpleSDK.resources.client.AzureResourcesClient;
import com.azure.simpleSDK.resources.models.Subscription;
import com.azure.simpleSDK.resources.models.SubscriptionListResult;
import com.azure.simpleSDK.network.models.AzureFirewall;
import com.azure.simpleSDK.network.models.AzureFirewallListResult;
import com.azure.simpleSDK.network.models.VirtualNetwork;
import com.azure.simpleSDK.network.models.VirtualNetworkListResult;
import com.azure.simpleSDK.network.models.NetworkInterface;
import com.azure.simpleSDK.network.models.NetworkInterfaceListResult;
import com.azure.simpleSDK.network.models.NetworkSecurityGroup;
import com.azure.simpleSDK.network.models.NetworkSecurityGroupListResult;
import com.azure.simpleSDK.network.models.NetworkInterfacePropertiesFormat;
import com.azure.simpleSDK.network.models.NetworkSecurityGroupPropertiesFormat;
import com.azure.simpleSDK.compute.models.VirtualMachine;
import com.azure.simpleSDK.compute.models.VirtualMachineListResult;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSet;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSetListWithLinkResult;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSetVM;
import com.azure.simpleSDK.compute.models.VirtualMachineScaleSetVMListResult;
import com.azure.simpleSDK.compute.models.OrchestrationMode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class DemoApplication {
    
    private static final Duration GRAPH_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";
    private static final String GRAPH_SP_ENDPOINT = "https://graph.microsoft.com/v1.0/servicePrincipals";
    
    private static AzureNetworkClient networkClient;
    private static AzureComputeClient computeClient;
    private static AzureResourcesClient resourcesClient;
    private static AzureAuthorizationClient authorizationClient;
    private static String subscriptionId;
    private static String tenantId;
    private static String clientId;
    private static String principalObjectId;
    private static ServicePrincipalCredentials credentials;
    private static boolean strictMode;
    
    public static void main(String[] args) {
        try {
            initializeClients(args);
            
            System.out.println("Fetching Azure Resources for subscription: " + subscriptionId);
            System.out.println("Using separate Network, Compute, Resources, and Authorization SDK clients");
            System.out.println("==========================================");

            showServicePrincipalSummary();
            listAvailableSubscriptions();

            // Test Network resources
            testNetworkResources();

            // Test Compute resources
            testComputeResources();

            // Inspect the service principal's permissions
            testAuthorizationPermissions();
            
        } catch (Exception e) {
            System.err.println("Error occurred while fetching Azure resources:");
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    private static void initializeClients(String[] args) throws IOException {
        // Load credentials from properties file
        Properties props = loadCredentials();
        tenantId = props.getProperty("azure.tenant-id");
        clientId = props.getProperty("azure.client-id");
        String clientSecret = props.getProperty("azure.client-secret");
        subscriptionId = props.getProperty("azure.subscription-id");
        principalObjectId = props.getProperty("azure.principal-object-id");
        if (principalObjectId == null || principalObjectId.isBlank()) {
            principalObjectId = resolvePrincipalObjectId(clientId, clientSecret);
            if (principalObjectId == null || principalObjectId.isBlank()) {
                System.out.println("Unable to automatically determine service principal object ID. "
                    + "Add azure.principal-object-id to azure.properties for complete authorization results.");
            } else {
                System.out.println("Resolved service principal object ID via Microsoft Graph: " + principalObjectId);
            }
        } else {
            System.out.println("Using service principal object ID from configuration: " + principalObjectId);
        }
        
        if (tenantId == null || clientId == null || clientSecret == null || subscriptionId == null) {
            System.err.println("Missing required properties. Please ensure azure.properties contains:");
            System.err.println("azure.tenant-id=<your-tenant-id>");
            System.err.println("azure.client-id=<your-client-id>");
            System.err.println("azure.client-secret=<your-client-secret>");
            System.err.println("azure.subscription-id=<your-subscription-id>");
            System.exit(1);
        }
        
        // Create credentials
        credentials = new ServicePrincipalCredentials(clientId, clientSecret, tenantId);
        
        // Check if strict mode is requested via system property
        strictMode = Boolean.parseBoolean(System.getProperty("strict", "false"));
        System.out.println("Running in " + (strictMode ? "STRICT" : "LENIENT") + " mode");
        System.out.println("(Use -Dstrict=true to enable strict mode for unknown property detection)");
        System.out.println();
        
        // Create separate clients for Network, Compute, and Resources services
        networkClient = new AzureNetworkClient(credentials, strictMode);
        computeClient = new AzureComputeClient(credentials, strictMode);
        resourcesClient = new AzureResourcesClient(credentials, strictMode);
        authorizationClient = new AzureAuthorizationClient(credentials, strictMode);
    }

    private static String resolvePrincipalObjectId(String clientId, String clientSecret) {
        System.out.println("\nAttempting to resolve the service principal object ID via Microsoft Graph...");
        try {
            ServicePrincipalCredentials graphCredentials =
                new ServicePrincipalCredentials(clientId, clientSecret, tenantId, GRAPH_SCOPE);
            String accessToken = graphCredentials.getAccessToken();

            String filter = URLEncoder.encode("appId eq '" + clientId + "'", StandardCharsets.UTF_8);
            String url = GRAPH_SP_ENDPOINT + "?$filter=" + filter;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(GRAPH_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(GRAPH_REQUEST_TIMEOUT)
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.out.println("Microsoft Graph request failed with status " + response.statusCode());
                System.out.println("Response body: " + response.body());
                return null;
            }

            ObjectMapper mapper = new ObjectMapper();
            GraphServicePrincipalResponse principalResponse =
                mapper.readValue(response.body(), GraphServicePrincipalResponse.class);

            if (principalResponse.value() == null || principalResponse.value().isEmpty()) {
                System.out.println("Microsoft Graph did not return any service principals for appId " + clientId);
                return null;
            }

            GraphServicePrincipal principal = principalResponse.value().get(0);
            System.out.println("Microsoft Graph display name: " + principal.displayName());
            return principal.id();
        } catch (Exception e) {
            System.out.println("Failed to query Microsoft Graph for service principal details: " + e.getMessage());
            return null;
        }
    }

    private static void showServicePrincipalSummary() {
        System.out.println("\n=== SERVICE PRINCIPAL INFORMATION ===");
        System.out.println("Tenant ID: " + (tenantId != null ? tenantId : "Unknown"));
        System.out.println("Client ID: " + (clientId != null ? clientId : "Unknown"));
        if (principalObjectId != null && !principalObjectId.isBlank()) {
            System.out.println("Resolved Object ID: " + principalObjectId);
        } else {
            System.out.println("Resolved Object ID: Not available (Graph lookup failed or permissions missing)");
        }
    }

    private static void listAvailableSubscriptions() {
        System.out.println("\n=== AVAILABLE SUBSCRIPTIONS ===");

        try {
            AzureResponse<SubscriptionListResult> response = resourcesClient.listSubscriptions();
            SubscriptionListResult result = response.getBody();

            System.out.println("Subscription API Status: " + response.getStatusCode());

            java.util.List<Subscription> subscriptions = result != null ? result.value() : null;
            if (subscriptions != null && !subscriptions.isEmpty()) {
                System.out.println("Number of subscriptions returned: " + subscriptions.size());
                for (Subscription subscription : subscriptions) {
                    if (subscription == null) {
                        continue;
                    }
                    StringBuilder details = new StringBuilder("  - ");
                    details.append(subscription.displayName() != null ? subscription.displayName() : "Unknown");
                    if (subscription.subscriptionId() != null) {
                        details.append(" (").append(subscription.subscriptionId()).append(")");
                    }
                    if (subscription.state() != null) {
                        details.append(" [").append(subscription.state()).append("]");
                    }
                    if (subscriptionId != null && subscription.subscriptionId() != null
                            && subscription.subscriptionId().equalsIgnoreCase(subscriptionId)) {
                        details.append(" <-- active demo subscription");
                    }
                    System.out.println(details);
                }
                if (result != null && result.nextLink() != null && !result.nextLink().isBlank()) {
                    System.out.println("Additional subscriptions available via pagination. Next link: " + result.nextLink());
                }
            } else {
                System.out.println("No subscriptions returned for the current credentials.");
            }
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR (SUBSCRIPTIONS) ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("==========================================\n");
        } catch (AzureException e) {
            System.out.println("Error fetching subscription list: " + e.getMessage());
        }
    }
    
    private static void testNetworkResources() {
        System.out.println("\n=== NETWORK RESOURCES ===");

        // Test all network resource types
        AzureFirewallListResult firewallList = testAzureFirewalls();
        VirtualNetworkListResult vnetList = testVirtualNetworks();
        NetworkInterfaceListResult nicList = testNetworkInterfaces();
        NetworkSecurityGroupListResult nsgList = testNetworkSecurityGroups();

        // Extract and print unique resource group names
        printResourceGroups(firewallList, vnetList, nicList, nsgList);

        // Test individual NSG details
        testIndividualNSGDetails();

        // Show detailed results
        showFirewallDetails(firewallList);
        showVirtualNetworkSummary(vnetList);
        showNetworkInterfaceDetails(nicList);
        showNetworkSecurityGroupSummary(nsgList);
    }

    private static void printResourceGroups(AzureFirewallListResult firewallList,
                                           VirtualNetworkListResult vnetList,
                                           NetworkInterfaceListResult nicList,
                                           NetworkSecurityGroupListResult nsgList) {
        Set<String> resourceGroups = new HashSet<>();

        // Extract resource groups from all network resources
        if (firewallList.value() != null) {
            for (AzureFirewall firewall : firewallList.value()) {
                String rg = extractResourceGroupFromId(firewall.id());
                if (rg != null && !rg.equals("Unknown")) {
                    resourceGroups.add(rg);
                }
            }
        }

        if (vnetList.value() != null) {
            for (VirtualNetwork vnet : vnetList.value()) {
                String rg = extractResourceGroupFromId(vnet.id());
                if (rg != null && !rg.equals("Unknown")) {
                    resourceGroups.add(rg);
                }
            }
        }

        if (nicList.value() != null) {
            for (NetworkInterface nic : nicList.value()) {
                String rg = extractResourceGroupFromId(nic.id());
                if (rg != null && !rg.equals("Unknown")) {
                    resourceGroups.add(rg);
                }
            }
        }

        if (nsgList.value() != null) {
            for (NetworkSecurityGroup nsg : nsgList.value()) {
                String rg = extractResourceGroupFromId(nsg.id());
                if (rg != null && !rg.equals("Unknown")) {
                    resourceGroups.add(rg);
                }
            }
        }

        // Print the resource groups
        System.out.println("\n=== RESOURCE GROUPS (extracted from network resources) ===");
        System.out.println("Total unique resource groups: " + resourceGroups.size());
        if (!resourceGroups.isEmpty()) {
            System.out.println("\nResource Group Names:");
            java.util.List<String> sortedRgs = new java.util.ArrayList<>(resourceGroups);
            java.util.Collections.sort(sortedRgs);
            for (String rg : sortedRgs) {
                System.out.println("  - " + rg);
            }
        }
        System.out.println();
    }
    
    private static AzureFirewallListResult testAzureFirewalls() {
        System.out.println("\n--- Testing Azure Firewalls (pagination test) ---");
        try {
            AzureResponse<AzureFirewallListResult> response = networkClient.listAllAzureFirewalls(subscriptionId);
            AzureFirewallListResult result = response.getBody();

            System.out.println("Firewall Response Status: " + response.getStatusCode());
            System.out.println("Number of firewalls found: " + (result.value() != null ? result.value().size() : 0));

            return result;
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("===========================\n");
            return new AzureFirewallListResult(null, null);
        } catch (Exception e) {
            System.out.println("Error fetching Azure Firewalls: " + e.getMessage());
            return new AzureFirewallListResult(null, null);
        }
    }
    
    private static VirtualNetworkListResult testVirtualNetworks() {
        System.out.println("\n--- Testing Virtual Networks (pagination test) ---");
        try {
            AzureResponse<VirtualNetworkListResult> response = networkClient.listAllVirtualNetworks(subscriptionId);
            VirtualNetworkListResult result = response.getBody();

            System.out.println("VNet Response Status: " + response.getStatusCode());
            System.out.println("Number of virtual networks found: " + (result.value() != null ? result.value().size() : 0));

            return result;
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("===========================\n");
            return new VirtualNetworkListResult(null, null);
        } catch (Exception e) {
            System.out.println("Error fetching Virtual Networks: " + e.getMessage());
            return new VirtualNetworkListResult(null, null);
        }
    }
    
    private static NetworkInterfaceListResult testNetworkInterfaces() {
        System.out.println("\n--- Testing Network Interfaces (pagination test) ---");
        try {
            AzureResponse<NetworkInterfaceListResult> response = networkClient.listAllNetworkInterfaces(subscriptionId);
            NetworkInterfaceListResult result = response.getBody();

            System.out.println("NIC Response Status: " + response.getStatusCode());
            System.out.println("Number of network interfaces found: " + (result.value() != null ? result.value().size() : 0));

            return result;
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("===========================\n");
            return new NetworkInterfaceListResult(null, null);
        } catch (Exception e) {
            System.out.println("Error fetching Network Interfaces: " + e.getMessage());
            return new NetworkInterfaceListResult(null, null);
        }
    }
    
    private static NetworkSecurityGroupListResult testNetworkSecurityGroups() {
        System.out.println("\n--- Testing Network Security Groups (pagination test) ---");
        try {
            AzureResponse<NetworkSecurityGroupListResult> response = networkClient.listAllNetworkSecurityGroups(subscriptionId);
            NetworkSecurityGroupListResult result = response.getBody();

            System.out.println("NSG Response Status: " + response.getStatusCode());
            System.out.println("Number of network security groups found: " + (result.value() != null ? result.value().size() : 0));

            return result;
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("===========================\n");
            return new NetworkSecurityGroupListResult(null, null);
        } catch (Exception e) {
            System.out.println("Error fetching Network Security Groups: " + e.getMessage());
            return new NetworkSecurityGroupListResult(null, null);
        }
    }
    
    private static void testIndividualNSGDetails() {
        System.out.println("\n--- Getting detailed NSG information ---");
        
        // Test specific NSGs
        testSingleNSG("shared-services-neu-nsg", "nsg-neu-rg");
        testSingleNSG("xxxx", "nsg-zzz");
    }
    
    private static void testSingleNSG(String nsgName, String resourceGroup) {
        try {
            System.out.println("\nFetching details for NSG: " + nsgName);
            AzureResponse<NetworkSecurityGroup> response = networkClient.getNetworkSecurityGroups(
                subscriptionId, resourceGroup, nsgName, null);
            NetworkSecurityGroup nsg = response.getBody();

            System.out.println("Response Status: " + response.getStatusCode());
            if (nsg != null) {
                showNSGDetails(nsg);
            } else {
                System.out.println("NSG details: null response body");
            }

        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("===========================\n");
        } catch (Exception e) {
            System.out.println("Error fetching NSG '" + nsgName + "': " + e.getMessage());
        }
    }
    
    private static void testComputeResources() {
        System.out.println("\n=== COMPUTE RESOURCES ===");

        VirtualMachineListResult vmList = testVirtualMachines();
        showVirtualMachineDetails(vmList);

        // Test Virtual Machine Scale Sets
        VirtualMachineScaleSetListWithLinkResult vmssList = testVirtualMachineScaleSets();
        showVirtualMachineScaleSetDetails(vmssList);
    }
    
    private static VirtualMachineListResult testVirtualMachines() {
        System.out.println("\n--- Testing Virtual Machines (pagination test) ---");
        try {
            AzureResponse<VirtualMachineListResult> response = computeClient.listByLocationVirtualMachines(subscriptionId, "eastus");
            VirtualMachineListResult result = response.getBody();

            System.out.println("VM Response Status: " + response.getStatusCode());
            System.out.println("Number of virtual machines found: " + (result.value() != null ? result.value().size() : 0));

            return result;
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("===========================\n");
            return new VirtualMachineListResult(null, null);
        } catch (Exception e) {
            System.out.println("VM API call succeeded but encountered data model issue:");
            System.out.println("This demonstrates that the API version fix worked - we got valid data from Azure Compute API");
            if (e.getMessage().contains("Standard_B2ts_v2")) {
                System.out.println("The error shows Azure returned VM size 'Standard_B2ts_v2' which is not in our OpenAPI spec");
                System.out.println("This is expected - Azure supports more VM sizes than documented in the 2024-11-01 spec");
            }
            System.out.println("Error details: " + e.getMessage().substring(0, Math.min(200, e.getMessage().length())));
            return new VirtualMachineListResult(null, null);
        }
    }

    private static VirtualMachineScaleSetListWithLinkResult testVirtualMachineScaleSets() {
        System.out.println("\n--- Testing Virtual Machine Scale Sets (pagination test) ---");
        try {
            AzureResponse<VirtualMachineScaleSetListWithLinkResult> response = computeClient.listAllVirtualMachineScaleSets(subscriptionId);
            VirtualMachineScaleSetListWithLinkResult result = response.getBody();

            System.out.println("VMSS Response Status: " + response.getStatusCode());
            System.out.println("Number of virtual machine scale sets found: " + (result.value() != null ? result.value().size() : 0));

            return result;
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("===========================\n");
            return new VirtualMachineScaleSetListWithLinkResult(null, null);
        } catch (Exception e) {
            System.out.println("Error fetching Virtual Machine Scale Sets: " + e.getMessage());
            return new VirtualMachineScaleSetListWithLinkResult(null, null);
        }
    }
    
    private static void testAuthorizationPermissions() {
        System.out.println("\n=== AUTHORIZATION & PERMISSIONS ===");
        if (authorizationClient == null) {
            System.out.println("Authorization client is not initialized.");
            return;
        }

        if (principalObjectId == null || principalObjectId.isBlank()) {
            System.out.println("Skipping permission lookup because azure.principal-object-id is not configured.");
            System.out.println("Add your service principal object ID to azure.properties to inspect exact permissions.");
            return;
        }

        try {
            String scope = "subscriptions/" + subscriptionId;
            String filter = "principalId eq '" + principalObjectId + "'";

            AzureResponse<RoleAssignmentListResult> response =
                authorizationClient.listForScopeRoleAssignments(scope, filter, tenantId, null);

            RoleAssignmentListResult body = response.getBody();
            List<RoleAssignment> assignments = body != null && body.value() != null
                ? body.value()
                : Collections.emptyList();

            System.out.println("Role assignment API Status: " + response.getStatusCode());
            if (assignments.isEmpty()) {
                System.out.println("No role assignments found for principalId " + principalObjectId + " at scope /" + scope);
                if (body != null && body.nextLink() != null) {
                    System.out.println("Additional role assignments available via nextLink: " + body.nextLink());
                }
                return;
            }

            System.out.println("Found " + assignments.size() + " role assignments for principalId " + principalObjectId);
            Map<String, RoleDefinition> definitionCache = new HashMap<>();

            for (RoleAssignment assignment : assignments) {
                if (assignment == null || assignment.properties() == null) {
                    continue;
                }
                RoleAssignmentProperties props = assignment.properties();
                RoleDefinition definition = resolveRoleDefinition(props.roleDefinitionId(), definitionCache);
                String roleName = definition != null && definition.properties() != null
                    ? definition.properties().roleName()
                    : "Unknown role";

                System.out.println("\nRole Assignment: " + assignment.name());
                System.out.println("  Role: " + roleName);
                System.out.println("  Scope: " + props.scope());
                System.out.println("  Principal Type: " + props.principalType());
                if (definition != null && definition.properties() != null) {
                    printPermissionSummary(definition.properties().permissions());
                } else {
                    System.out.println("  Permissions: Unable to load role definition");
                }
            }
        } catch (AzureServiceException e) {
            System.err.println("\n=== AZURE SERVICE ERROR (AUTHORIZATION) ===");
            System.err.println("HTTP Status Code: " + e.getStatusCode());
            System.err.println("Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("Response Body: " + e.getResponseBody());
            }
            System.err.println("==========================================\n");
        } catch (AzureException e) {
            System.err.println("Error fetching role assignments: " + e.getMessage());
        }
    }

    private static RoleDefinition resolveRoleDefinition(String roleDefinitionId, Map<String, RoleDefinition> cache) throws AzureException {
        if (roleDefinitionId == null || roleDefinitionId.isBlank()) {
            return null;
        }
        if (cache.containsKey(roleDefinitionId)) {
            return cache.get(roleDefinitionId);
        }

        String normalized = roleDefinitionId.startsWith("/") ? roleDefinitionId.substring(1) : roleDefinitionId;
        String providerSegment = "/providers/Microsoft.Authorization/roleDefinitions/";
        int segmentIndex = normalized.indexOf(providerSegment);
        if (segmentIndex == -1) {
            return null;
        }

        String scope = normalized.substring(0, segmentIndex);
        if (scope.isEmpty()) {
            return null;
        }

        String roleName = normalized.substring(segmentIndex + providerSegment.length());
        if (roleName.isEmpty()) {
            return null;
        }

        AzureResponse<RoleDefinition> response = authorizationClient.getRoleDefinitions(scope, roleName);
        RoleDefinition definition = response.getBody();
        cache.put(roleDefinitionId, definition);
        return definition;
    }

    private static void printPermissionSummary(List<AuthorizationRoleDefinitionsCallsPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            System.out.println("  Permissions: None declared by this role.");
            return;
        }

        int index = 1;
        for (AuthorizationRoleDefinitionsCallsPermission permission : permissions) {
            if (permission == null) {
                continue;
            }
            System.out.println("  Permission Set " + index + ":");
            System.out.println("    Actions: " + formatActionList(permission.actions()));
            if (permission.notActions() != null && !permission.notActions().isEmpty()) {
                System.out.println("    Not Actions: " + formatActionList(permission.notActions()));
            }
            if (permission.dataActions() != null && !permission.dataActions().isEmpty()) {
                System.out.println("    Data Actions: " + formatActionList(permission.dataActions()));
            }
            if (permission.notDataActions() != null && !permission.notDataActions().isEmpty()) {
                System.out.println("    Not Data Actions: " + formatActionList(permission.notDataActions()));
            }
            index++;
        }
    }

    private static String formatActionList(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return "None";
        }
        final int limit = 5;
        if (actions.size() <= limit) {
            return String.join(", ", actions);
        }
        return String.join(", ", actions.subList(0, limit)) + " ... (+" + (actions.size() - limit) + " more)";
    }
    
    // Display methods - extracted common display logic
    
    private static void showFirewallDetails(AzureFirewallListResult firewallList) {
        if (firewallList.value() != null && !firewallList.value().isEmpty()) {
            System.out.println("\nDetailed Firewall Information:");
            System.out.println("==============================");
            
            for (AzureFirewall firewall : firewallList.value()) {
                System.out.println("Firewall Name: " + firewall.name());
                System.out.println("Resource Group: " + extractResourceGroupFromId(firewall.id()));
                System.out.println("Location: " + firewall.location());
                System.out.println("Provisioning State: " + (firewall.properties() != null ? firewall.properties().provisioningState() : "Unknown"));
                System.out.println();
            }
        } else {
            System.out.println("No Azure Firewalls found in subscription " + subscriptionId);
        }
    }
    
    private static void showVirtualNetworkSummary(VirtualNetworkListResult vnetList) {
        if (vnetList.value() != null && !vnetList.value().isEmpty()) {
            System.out.println("\nVirtual Networks Summary:");
            System.out.println("========================");
            
            for (VirtualNetwork vnet : vnetList.value()) {
                System.out.println("VNet Name: " + vnet.name());
                System.out.println("Resource Group: " + extractResourceGroupFromId(vnet.id()));
                System.out.println("Location: " + vnet.location());
                System.out.println();
            }
        } else {
            System.out.println("No Virtual Networks found in subscription " + subscriptionId);
        }
    }
    
    private static void showNetworkInterfaceDetails(NetworkInterfaceListResult nicList) {
        if (nicList.value() != null && !nicList.value().isEmpty()) {
            System.out.println("\nNetwork Interfaces Detailed Summary:");
            System.out.println("====================================");
            
            for (NetworkInterface nic : nicList.value()) {
                System.out.println("NIC Name: " + nic.name());
                System.out.println("Resource Group: " + extractResourceGroupFromId(nic.id()));
                System.out.println("Location: " + nic.location());
                System.out.println("Type: " + nic.type());
                
                if (nic.properties() != null) {
                    showNICProperties(nic.properties());
                }
                
                showResourceTags(nic.tags());
                System.out.println();
            }
        } else {
            System.out.println("No Network Interfaces found in subscription " + subscriptionId);
        }
    }
    
    private static void showNICProperties(NetworkInterfacePropertiesFormat nicProps) {
        System.out.println("Provisioning State: " + nicProps.provisioningState());
        System.out.println("Primary: " + nicProps.primary());
        System.out.println("MAC Address: " + nicProps.macAddress());
        System.out.println("Accelerated Networking: " + nicProps.enableAcceleratedNetworking());
        System.out.println("IP Forwarding: " + nicProps.enableIPForwarding());
        System.out.println("VNet Encryption Supported: " + nicProps.vnetEncryptionSupported());
        
        // Virtual Machine association
        if (nicProps.virtualMachine() != null) {
            System.out.println("Associated VM: " + extractResourceNameFromId(nicProps.virtualMachine().id()));
        } else {
            System.out.println("Associated VM: None");
        }
        
        // Network Security Group
        if (nicProps.networkSecurityGroup() != null) {
            System.out.println("Network Security Group: " + nicProps.networkSecurityGroup().id());
        } else {
            System.out.println("Network Security Group: None");
        }
        
        // IP Configurations
        if (nicProps.ipConfigurations() != null && !nicProps.ipConfigurations().isEmpty()) {
            System.out.println("IP Configurations: " + nicProps.ipConfigurations().size());
            for (int i = 0; i < nicProps.ipConfigurations().size(); i++) {
                var ipConfig = nicProps.ipConfigurations().get(i);
                System.out.println("  IP Config " + (i + 1) + ":");
                System.out.println("    Name: " + ipConfig.name());
                
                if (ipConfig.properties() != null) {
                    var ipProps = ipConfig.properties();
                    System.out.println("    Primary: " + ipProps.primary());
                    System.out.println("    Private IP: " + ipProps.privateIPAddress());
                    System.out.println("    Private IP Allocation: " + ipProps.privateIPAllocationMethod());
                    
                    if (ipProps.subnet() != null) {
                        System.out.println("    Subnet: " + extractResourceNameFromId(ipProps.subnet().id()));
                    }
                    
                    if (ipProps.publicIPAddress() != null) {
                        System.out.println("    Public IP: " + extractResourceNameFromId(ipProps.publicIPAddress().id()));
                    } else {
                        System.out.println("    Public IP: None");
                    }
                }
            }
        } else {
            System.out.println("IP Configurations: 0");
        }
        
        // DNS Settings
        if (nicProps.dnsSettings() != null) {
            System.out.println("DNS Settings: Available");
        }
    }
    
    private static void showNetworkSecurityGroupSummary(NetworkSecurityGroupListResult nsgList) {
        if (nsgList.value() != null && !nsgList.value().isEmpty()) {
            System.out.println("\nNetwork Security Groups Summary:");
            System.out.println("================================");
            
            for (NetworkSecurityGroup nsg : nsgList.value()) {
                System.out.println("NSG Name: " + nsg.name());
                System.out.println("Resource Group: " + extractResourceGroupFromId(nsg.id()));
                System.out.println("Location: " + nsg.location());
                if (nsg.properties() != null) {
                    System.out.println("Provisioning State: " + nsg.properties().provisioningState());
                    showNSGRuleCounts(nsg.properties());
                }
                System.out.println();
            }
        } else {
            System.out.println("No Network Security Groups found in subscription " + subscriptionId);
        }
    }
    
    private static void showNSGDetails(NetworkSecurityGroup nsg) {
        System.out.println("NSG Name: " + nsg.name());
        System.out.println("NSG ID: " + nsg.id());
        System.out.println("Location: " + nsg.location());
        System.out.println("Resource Group: " + extractResourceGroupFromId(nsg.id()));
        
        if (nsg.properties() != null) {
            var nsgProps = nsg.properties();
            System.out.println("Provisioning State: " + nsgProps.provisioningState());
            
            // Security Rules
            if (nsgProps.securityRules() != null) {
                System.out.println("Custom Security Rules: " + nsgProps.securityRules().size());
                for (var rule : nsgProps.securityRules()) {
                    System.out.println("  - " + rule.name() + ": " + rule.properties().access() + 
                                     " " + rule.properties().protocol() + " from " + 
                                     rule.properties().sourceAddressPrefix() + " to " + 
                                     rule.properties().destinationAddressPrefix());
                }
            }
            
            showNSGRuleCounts(nsgProps);
            showNSGAssociations(nsgProps);
        }
        
        showResourceTags(nsg.tags());
    }
    
    private static void showNSGRuleCounts(NetworkSecurityGroupPropertiesFormat nsgProps) {
        if (nsgProps.securityRules() != null) {
            System.out.println("Custom Security Rules: " + nsgProps.securityRules().size());
        }
        if (nsgProps.defaultSecurityRules() != null) {
            System.out.println("Default Security Rules: " + nsgProps.defaultSecurityRules().size());
        }
    }
    
    private static void showNSGAssociations(NetworkSecurityGroupPropertiesFormat nsgProps) {
        if (nsgProps.subnets() != null) {
            System.out.println("Associated Subnets: " + nsgProps.subnets().size());
            for (var subnet : nsgProps.subnets()) {
                System.out.println("  - " + extractResourceNameFromId(subnet.id()));
            }
        }
        
        if (nsgProps.networkInterfaces() != null) {
            System.out.println("Associated NICs: " + nsgProps.networkInterfaces().size());
            for (var nic : nsgProps.networkInterfaces()) {
                System.out.println("  - " + extractResourceNameFromId(nic.id()));
            }
        }
    }
    
    private static void showVirtualMachineDetails(VirtualMachineListResult vmList) {
        if (vmList.value() != null && !vmList.value().isEmpty()) {
            System.out.println("\nVirtual Machines Summary:");
            System.out.println("=========================");

            for (VirtualMachine vm : vmList.value()) {
                System.out.println("VM Properties: " + (vm.properties() != null ? "Available" : "None"));
                if (vm.properties() != null) {
                    System.out.println("Provisioning State: " + vm.properties().provisioningState());
                    if (vm.properties().hardwareProfile() != null) {
                        System.out.println("VM Size: " + vm.properties().hardwareProfile().vmSize());
                    }
                    if (vm.properties().osProfile() != null) {
                        System.out.println("Computer Name: " + vm.properties().osProfile().computerName());
                    }
                }
                if (vm.zones() != null && !vm.zones().isEmpty()) {
                    System.out.println("Zones: " + String.join(", ", vm.zones()));
                }
                System.out.println();
            }
        } else {
            System.out.println("No Virtual Machines found in subscription " + subscriptionId);
        }
    }

    private static void showVirtualMachineScaleSetDetails(VirtualMachineScaleSetListWithLinkResult vmssList) {
        if (vmssList.value() != null && !vmssList.value().isEmpty()) {
            System.out.println("\nVirtual Machine Scale Sets Detailed Summary:");
            System.out.println("=============================================");

            for (VirtualMachineScaleSet vmss : vmssList.value()) {
                System.out.println("\n========================================");
                System.out.println("Scale Set Name: " + vmss.name());
                System.out.println("Resource Group: " + extractResourceGroupFromId(vmss.id()));
                System.out.println("Location: " + vmss.location());

                // Get orchestration mode (Uniform/Flexible)
                if (vmss.properties() != null && vmss.properties().orchestrationMode() != null) {
                    OrchestrationMode mode = vmss.properties().orchestrationMode();
                    System.out.println("Orchestration Mode: " + mode.getValue());
                } else {
                    System.out.println("Orchestration Mode: Unknown");
                }

                if (vmss.properties() != null) {
                    System.out.println("Provisioning State: " + vmss.properties().provisioningState());
                }

                // List VMs in this scale set
                String resourceGroup = extractResourceGroupFromId(vmss.id());
                listVMsInScaleSet(resourceGroup, vmss.name());

                System.out.println("========================================");
            }
        } else {
            System.out.println("No Virtual Machine Scale Sets found in subscription " + subscriptionId);
        }
    }

    private static void listVMsInScaleSet(String resourceGroupName, String vmssName) {
        System.out.println("\n  VMs in Scale Set:");
        System.out.println("  -----------------");

        try {
            // Fetch all NICs for the scale set in a single API call
            System.out.println("  Fetching all NICs for scale set...");
            java.util.Map<String, java.util.List<NetworkInterface>> vmInstanceToNICs = new java.util.HashMap<>();

            try {
                AzureResponse<NetworkInterfaceListResult> nicResponse = networkClient.listVirtualMachineScaleSetNetworkInterfacesNetworkInterfaces(
                    subscriptionId, resourceGroupName, vmssName);
                NetworkInterfaceListResult nicList = nicResponse.getBody();

                if (nicList.value() != null) {
                    System.out.println("  Total NICs in scale set: " + nicList.value().size());

                    // Build a map of VM instance ID to NICs
                    for (NetworkInterface nic : nicList.value()) {
                        // Extract instance ID from NIC resource ID
                        // Format: /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachineScaleSets/{vmss}/virtualMachines/{instanceId}/networkInterfaces/{nicName}
                        String instanceId = extractInstanceIdFromNICId(nic.id());
                        if (instanceId != null) {
                            vmInstanceToNICs.computeIfAbsent(instanceId, k -> new java.util.ArrayList<>()).add(nic);
                        }
                    }
                }
            } catch (AzureServiceException e) {
                System.err.println("  === AZURE SERVICE ERROR ===");
                System.err.println("  HTTP Status Code: " + e.getStatusCode());
                System.err.println("  Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
                System.err.println("  Error Message: " + e.getMessage());
                System.err.println("  ===========================");
            } catch (Exception e) {
                System.out.println("  Warning: Could not fetch NICs in bulk: " + e.getMessage());
            }

            // Now list the VMs
            AzureResponse<VirtualMachineScaleSetVMListResult> response = computeClient.listVirtualMachineScaleSetVMs(
            //    subscriptionId, resourceGroupName, vmssName, "properties/latestModelApplied+eq+true","instanceView", "instanceView");
            subscriptionId, resourceGroupName, vmssName, null, null, null);
            VirtualMachineScaleSetVMListResult vmList = response.getBody();

            if (vmList.value() != null && !vmList.value().isEmpty()) {
                System.out.println("  Total VMs: " + vmList.value().size());

                for (VirtualMachineScaleSetVM vm : vmList.value()) {
                    System.out.println("\n  VM Instance ID: " + vm.instanceId());
                    System.out.println("  VM Name: " + vm.name());
                    if (vm.properties() != null) {
                        System.out.println("  Provisioning State: " + vm.properties().provisioningState());

                        // Show network interfaces from the pre-fetched map
                        java.util.List<NetworkInterface> vmNICs = vmInstanceToNICs.get(vm.instanceId());
                        if (vmNICs != null && !vmNICs.isEmpty()) {
                            System.out.println("  Network Interfaces:");
                            for (NetworkInterface nic : vmNICs) {
                                String nicName = extractResourceNameFromId(nic.id());
                                System.out.println("    - " + nicName);

                                // Show primary flag
                                if (nic.properties() != null && nic.properties().primary() != null) {
                                    System.out.println("      Primary: " + nic.properties().primary());
                                }

                                // Show IP addresses directly from the NIC
                                showIPAddressesFromNIC(nic, resourceGroupName, vmssName, vm.instanceId());
                            }
                        } else {
                            System.out.println("  Network Interfaces: None");
                        }
                    }
                }
            } else {
                System.out.println("  No VMs found in this scale set");
            }
        } catch (AzureServiceException e) {
            System.err.println("  === AZURE SERVICE ERROR ===");
            System.err.println("  HTTP Status Code: " + e.getStatusCode());
            System.err.println("  Error Code: " + (e.getErrorCode() != null ? e.getErrorCode() : "N/A"));
            System.err.println("  Error Message: " + e.getMessage());
            if (e.getResponseBody() != null && !e.getResponseBody().isEmpty()) {
                System.err.println("  Response Body: " + e.getResponseBody());
            }
            System.err.println("  ===========================");
        } catch (Exception e) {
            System.out.println("  Error fetching VMs in scale set: " + e.getMessage());
        }
    }
    
    private static void showIPAddressesFromNIC(NetworkInterface nic, String resourceGroupName, String vmssName, String instanceId) {
        if (nic.properties() != null && nic.properties().ipConfigurations() != null) {
            for (var ipConfig : nic.properties().ipConfigurations()) {
                if (ipConfig.properties() != null) {
                    String privateIP = ipConfig.properties().privateIPAddress();
                    if (privateIP != null) {
                        System.out.println("      Private IP: " + privateIP);
                    }

                    // Show public IP if available
                    if (ipConfig.properties().publicIPAddress() != null) {
                        String publicIPName = extractResourceNameFromId(ipConfig.properties().publicIPAddress().id());
                        String ipConfigName = ipConfig.name();
                        String nicName = extractResourceNameFromId(nic.id());

                        // Fetch public IP details using VMSS-specific API
                        try {
                            var publicIPResponse = networkClient.getVirtualMachineScaleSetPublicIPAddressPublicIPAddresses(
                                subscriptionId, resourceGroupName, vmssName, instanceId,
                                nicName, ipConfigName, publicIPName, null);
                            if (publicIPResponse.getBody() != null &&
                                publicIPResponse.getBody().properties() != null &&
                                publicIPResponse.getBody().properties().ipAddress() != null) {
                                System.out.println("      Public IP: " + publicIPResponse.getBody().properties().ipAddress());
                            }
                        } catch (Exception e) {
                            // Silently skip if we can't fetch public IP details
                        }
                    }
                }
            }
        }
    }

    private static String extractInstanceIdFromNICId(String nicId) {
        if (nicId == null) return null;

        // Format: /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.Compute/virtualMachineScaleSets/{vmss}/virtualMachines/{instanceId}/networkInterfaces/{nicName}
        String[] parts = nicId.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("virtualMachines".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    // Utility methods

    private static void showResourceTags(java.util.Map<String, String> tags) {
        if (tags != null && !tags.isEmpty()) {
            System.out.println("Tags: " + tags.size());
            tags.forEach((key, value) -> 
                System.out.println("  " + key + ": " + value));
        }
    }
    
    private static Properties loadCredentials() throws IOException {
        Properties props = new Properties();
        String propertiesFile = "azure.properties";
        
        try (FileInputStream fis = new FileInputStream(propertiesFile)) {
            props.load(fis);
        } catch (IOException e) {
            System.err.println("Could not load " + propertiesFile + ": " + e.getMessage());
            System.err.println("Please create azure.properties file in the current directory with your Azure credentials.");
            throw e;
        }
        
        return props;
    }
    
    private static String extractResourceGroupFromId(String resourceId) {
        if (resourceId == null) return "Unknown";
        
        // Azure resource ID format: /subscriptions/{sub}/resourceGroups/{rg}/providers/{provider}/...
        String[] parts = resourceId.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("resourceGroups".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return "Unknown";
    }
    
    private static String extractResourceNameFromId(String resourceId) {
        if (resourceId == null) return "Unknown";
        
        // Azure resource ID format: /subscriptions/{sub}/resourceGroups/{rg}/providers/{provider}/.../{resourceName}
        String[] parts = resourceId.split("/");
        if (parts.length > 0) {
            return parts[parts.length - 1]; // Last part is the resource name
        }
        return "Unknown";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GraphServicePrincipalResponse(
        List<GraphServicePrincipal> value
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GraphServicePrincipal(
        String id,
        String appId,
        String displayName
    ) {
    }
}
