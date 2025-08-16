package com.azure.simpleSDK.demo;

import com.azure.simpleSDK.client.AzureSimpleSDKClient;
import com.azure.simpleSDK.http.AzureResponse;
import com.azure.simpleSDK.http.auth.ServicePrincipalCredentials;
import com.azure.simpleSDK.models.AzureFirewall;
import com.azure.simpleSDK.models.AzureFirewallListResult;
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
            
            // Create credentials and client
            ServicePrincipalCredentials credentials = new ServicePrincipalCredentials(clientId, clientSecret, tenantId);
            AzureSimpleSDKClient client = new AzureSimpleSDKClient(credentials);
            
            System.out.println("Fetching Azure Firewalls for subscription: " + subscriptionId);
            System.out.println("==========================================");
            
            // Get all Azure Firewalls in the subscription
            AzureResponse<AzureFirewallListResult> response = client.listAllAzureFirewalls(subscriptionId);
            AzureFirewallListResult firewallList = response.getBody();
            
            // Pretty print the results
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            
            System.out.println("Response Status: " + response.getStatusCode());
            System.out.println("Number of firewalls found: " + (firewallList.value() != null ? firewallList.value().size() : 0));
            System.out.println();
            
            if (firewallList.value() != null && !firewallList.value().isEmpty()) {
                System.out.println("Azure Firewalls:");
                System.out.println("================");
                
                for (AzureFirewall firewall : firewallList.value()) {
                    System.out.println("Firewall Name: " + firewall.name());
                    System.out.println("Resource Group: " + extractResourceGroupFromId(firewall.id()));
                    System.out.println("Location: " + firewall.location());
                    System.out.println("Provisioning State: " + (firewall.properties() != null ? firewall.properties().provisioningState() : "Unknown"));
                    System.out.println("ID: " + firewall.id());
                    System.out.println();
                }
                
                System.out.println("Full JSON Response:");
                System.out.println("==================");
                System.out.println(mapper.writeValueAsString(firewallList));
            } else {
                System.out.println("No Azure Firewalls found in subscription " + subscriptionId);
            }
            
        } catch (Exception e) {
            System.err.println("Error occurred while fetching Azure Firewalls:");
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