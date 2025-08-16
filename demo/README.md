# Azure Simple SDK Demo

This demo application shows how to use the generated Azure Simple SDK to authenticate with Azure and fetch Azure Firewalls from a subscription.

## Prerequisites

1. Azure subscription with appropriate permissions
2. Azure Service Principal with read access to Azure resources
3. Java 17 or higher
4. Gradle (included via wrapper)

## Setup

1. **Create Azure Service Principal** (if you don't have one):
   ```bash
   az ad sp create-for-rbac --name "azure-simple-sdk-demo" --role "Reader" --scopes "/subscriptions/YOUR_SUBSCRIPTION_ID"
   ```

2. **Copy the credentials template**:
   ```bash
   cp azure.properties.template azure.properties
   ```

3. **Fill in your Azure credentials** in `azure.properties`:
   ```properties
   azure.tenant-id=YOUR_TENANT_ID_HERE
   azure.client-id=YOUR_CLIENT_ID_HERE
   azure.client-secret=YOUR_CLIENT_SECRET_HERE
   azure.subscription-id=YOUR_SUBSCRIPTION_ID_HERE
   ```

## Running the Demo

From the project root directory:

```bash
# Build and run the demo
./gradlew :demo:run

# Or build and run manually
./gradlew :demo:build
cd demo
java -jar build/libs/demo.jar
```

## What the Demo Does

1. **Loads credentials** from `azure.properties` file
2. **Creates Azure client** using Service Principal authentication
3. **Fetches all Azure Firewalls** in the specified subscription
4. **Displays results** including:
   - Number of firewalls found
   - Basic firewall information (name, resource group, location, status)
   - Full JSON response for detailed inspection

## Sample Output

```
Fetching Azure Firewalls for subscription: 12345678-1234-1234-1234-123456789abc
==========================================
Response Status: 200
Number of firewalls found: 2

Azure Firewalls:
================
Firewall Name: my-firewall-1
Resource Group: my-rg-1
Location: eastus
Provisioning State: Succeeded
ID: /subscriptions/.../resourceGroups/my-rg-1/providers/Microsoft.Network/azureFirewalls/my-firewall-1

Firewall Name: my-firewall-2
Resource Group: my-rg-2
Location: westus2
Provisioning State: Succeeded
ID: /subscriptions/.../resourceGroups/my-rg-2/providers/Microsoft.Network/azureFirewalls/my-firewall-2

Full JSON Response:
==================
{
  "value" : [ ... ]
}
```

## Troubleshooting

### Authentication Errors
- Verify your credentials in `azure.properties`
- Ensure the Service Principal has proper permissions
- Check that the tenant ID and subscription ID are correct

### No Firewalls Found
- This is normal if your subscription doesn't have any Azure Firewalls
- The demo will still show a successful response with 0 firewalls

### Build Errors
- Ensure Java 17+ is installed
- Run `./gradlew clean build` to rebuild everything

## Extending the Demo

You can modify `DemoApplication.java` to:
- Try other SDK methods (e.g., `listAllFirewallPolicies`, `listAllWebApplicationFirewallPolicies`)
- Add filtering or specific resource group queries
- Export results to different formats
- Add error handling for specific scenarios

## Available SDK Methods

The generated SDK includes many firewall-related methods:
- `listAllAzureFirewalls(subscriptionId)`
- `listAzureFirewalls(subscriptionId, resourceGroupName)`
- `getAzureFirewalls(subscriptionId, resourceGroupName, firewallName)`
- `listAllFirewallPolicies(subscriptionId)`
- `listAllWebApplicationFirewallPolicies(subscriptionId)`
- And many more...

See `AzureSimpleSDKClient.java` for the complete list of available operations.