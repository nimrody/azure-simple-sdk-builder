package com.azure.simpleSDK.demo;

import com.azure.simpleSDK.network.client.AzureNetworkClient;
import com.azure.simpleSDK.compute.client.AzureComputeClient;
import com.azure.simpleSDK.http.AzureResponse;
import com.azure.simpleSDK.http.auth.ServicePrincipalCredentials;
import com.azure.simpleSDK.network.models.AzureFirewall;
import com.azure.simpleSDK.network.models.AzureFirewallListResult;
import com.azure.simpleSDK.network.models.VirtualNetwork;
import com.azure.simpleSDK.network.models.VirtualNetworkListResult;
import com.azure.simpleSDK.network.models.NetworkInterface;
import com.azure.simpleSDK.network.models.NetworkInterfaceListResult;
import com.azure.simpleSDK.compute.models.VirtualMachine;
import com.azure.simpleSDK.compute.models.VirtualMachineListResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class DemoApplication {
    
    public static void main(String[] args) {
        try {
            // Load credentials from properties file
            Properties props = loadCredentials();
            String tenantId = props.getProperty("azure.tenant-id");
            String clientId = props.getProperty("azure.client-id");
            String clientSecret = props.getProperty("azure.client-secret");
            String subscriptionId = props.getProperty("azure.subscription-id");
            
            if (tenantId == null || clientId == null || clientSecret == null || subscriptionId == null) {
                System.err.println("Missing required properties. Please ensure azure.properties contains:");
                System.err.println("azure.tenant-id=<your-tenant-id>");
                System.err.println("azure.client-id=<your-client-id>");
                System.err.println("azure.client-secret=<your-client-secret>");
                System.err.println("azure.subscription-id=<your-subscription-id>");
                System.exit(1);
            }
            
            // Create credentials
            ServicePrincipalCredentials credentials = new ServicePrincipalCredentials(clientId, clientSecret, tenantId);
            
            // Check if strict mode is requested via system property
            boolean strictMode = Boolean.parseBoolean(System.getProperty("strict", "false"));
            System.out.println("Running in " + (strictMode ? "STRICT" : "LENIENT") + " mode");
            System.out.println("(Use -Dstrict=true to enable strict mode for unknown property detection)");
            System.out.println();
            
            // Create separate clients for Network and Compute services
            AzureNetworkClient networkClient = new AzureNetworkClient(credentials, strictMode);
            AzureComputeClient computeClient = new AzureComputeClient(credentials, strictMode);
            
            System.out.println("Fetching Azure Resources for subscription: " + subscriptionId);
            System.out.println("Using separate Network and Compute SDK clients");
            System.out.println("==========================================");
            
            // Test Network resources
            System.out.println("\n=== NETWORK RESOURCES ===");
            
            // First test: Get all Azure Firewalls
            System.out.println("\n--- Testing Azure Firewalls (pagination test) ---");
            AzureResponse<AzureFirewallListResult> firewallResponse = networkClient.listAllAzureFirewalls(subscriptionId);
            AzureFirewallListResult firewallList = firewallResponse.getBody();
            
            System.out.println("Firewall Response Status: " + firewallResponse.getStatusCode());
            System.out.println("Number of firewalls found: " + (firewallList.value() != null ? firewallList.value().size() : 0));
            
            // Second test: Get all Virtual Networks (more likely to have pagination)
            System.out.println("\n--- Testing Virtual Networks (pagination test) ---");
            AzureResponse<VirtualNetworkListResult> vnetResponse = networkClient.listAllVirtualNetworks(subscriptionId);
            VirtualNetworkListResult vnetList = vnetResponse.getBody();
            
            System.out.println("VNet Response Status: " + vnetResponse.getStatusCode());
            System.out.println("Number of virtual networks found: " + (vnetList.value() != null ? vnetList.value().size() : 0));
            
            // Third test: Get all Network Interfaces (most likely to have pagination)
            System.out.println("\n--- Testing Network Interfaces (pagination test) ---");
            AzureResponse<NetworkInterfaceListResult> nicResponse = networkClient.listAllNetworkInterfaces(subscriptionId);
            NetworkInterfaceListResult nicList = nicResponse.getBody();
            
            System.out.println("NIC Response Status: " + nicResponse.getStatusCode());
            System.out.println("Number of network interfaces found: " + (nicList.value() != null ? nicList.value().size() : 0));
            
            // Test Compute resources
            System.out.println("\n=== COMPUTE RESOURCES ===");
            
            // Test: Get all Virtual Machines (uses listAllVirtualMachines which accepts subscription-wide listing)
            System.out.println("\n--- Testing Virtual Machines (pagination test) ---");
            // Note: listAllVirtualMachines requires statusOnly, $filter, $expand parameters
            AzureResponse<VirtualMachineListResult> vmResponse = computeClient.listAllVirtualMachines(subscriptionId, null, null, null);
            VirtualMachineListResult vmList = vmResponse.getBody();
            
            System.out.println("VM Response Status: " + vmResponse.getStatusCode());
            System.out.println("Number of virtual machines found: " + (vmList.value() != null ? vmList.value().size() : 0));
            
            // Show detailed results for firewalls
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
            
            // Show basic virtual network information
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
            
            // Show basic network interface information  
            if (nicList.value() != null && !nicList.value().isEmpty()) {
                System.out.println("\nNetwork Interfaces Summary:");
                System.out.println("===========================");
                
                for (NetworkInterface nic : nicList.value()) {
                    System.out.println("NIC Name: " + nic.name());
                    System.out.println("Resource Group: " + extractResourceGroupFromId(nic.id()));
                    System.out.println("Location: " + nic.location());
                    if (nic.properties() != null) {
                        System.out.println("Provisioning State: " + nic.properties().provisioningState());
                        System.out.println("Primary: " + nic.properties().primary());
                    }
                    System.out.println();
                }
            } else {
                System.out.println("No Network Interfaces found in subscription " + subscriptionId);
            }
            
            // Show detailed results for virtual machines
            if (vmList.value() != null && !vmList.value().isEmpty()) {
                System.out.println("\nVirtual Machines Summary:");
                System.out.println("=========================");
                
                for (VirtualMachine vm : vmList.value()) {
                    // Note: The generated VirtualMachine record is missing basic Azure resource fields (name, id, location)
                    // This appears to be an issue with the OpenAPI definition not including base resource properties
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
            
        } catch (Exception e) {
            System.err.println("Error occurred while fetching Azure resources:");
            e.printStackTrace();
            System.exit(1);
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
}