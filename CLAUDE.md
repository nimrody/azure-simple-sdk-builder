# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with
code in this repository.

This is a Java command line tool to create a library to replace the broken
Azure SDK. 

The tool will read specifications from the folder
azure-rest-api-specs/specification and generate the necessary model files and
code to call the relevant REST API endpoint. 

The tool accepts a list of API calls to generate code for. E.g., specifying
`VirtualNetworkGateways_List` will find the endpoint at
azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/stable/2024-07-01/virtualNetworkGateway.json
and create models and code for this endpoint.

## Features

* Instead of relying on the Reactor async library, the generated code is using
  the built Java 17 HTTP synchronous client. 

* The generated code parses Azure JSON responses using Jackson ObjectMapper.
  All models created from the HTTP response data will use immutable Java
  records.

* When the same model appears in multiple files and paths contain date, use the
  file where the date is latest.

* Built with Gradle and requires Java 17+

## Project Structure

This is a multi-module Gradle project with three main components:

- **app/** - Main application module containing the code generation tool

- **sdk/** - Generated SDK module that will contain the final Azure Simple SDK
  library

- **demo/** - Demonstration application showing how to use the generated SDK

- **azure-rest-api-specs/** - Git submodule containing Microsoft Azure REST API
  specifications

## Build Commands

The project uses Gradle with the wrapper script. All commands should be run from the root directory:

```bash
# Build the entire project
./gradlew build

# Build and run the code generation tool
./gradlew :app:run

# Build only the SDK module
./gradlew :sdk:build

# Build and run the demo application
./gradlew :demo:run

# Run tests
./gradlew test

# Clean build artifacts
./gradlew clean
```

## Development Setup

1. Ensure Java 17+ is installed
2. The project uses Gradle 7.6.2 (via wrapper)
3. Azure REST API specifications are located in `azure-rest-api-specs/specification/`
4. Generated code will be placed in the `sdk/` module

## Code Generation Process

The tool processes Azure OpenAPI specifications from the azure-rest-api-specs directory:

1. **Specification Loading**: Two-phase loading process for comprehensive coverage:
   - **Phase 1**: Scans all JSON files in the azure-rest-api-specs directory and extracts:
     - Operations from the `paths` section
     - Model definitions from the `definitions` section
     - Records the file name and line number where each definition appears for traceability
     - Collects external references (`./file.json#/definitions/Type` and `../path/file.json#/definitions/Type`)
   - **Phase 2**: Loads external referenced files recursively to resolve missing type definitions

2. **External Reference Resolution**: 
   - Supports relative path references like `./networkManagerEffectiveConfiguration.json#/definitions/ConfigurationGroup`
   - Handles cross-directory references like `../../../../../common-types/resource-management/v5/types.json#/parameters/ApiVersionParameter`
   - Recursively processes external files to find additional external references
   - Prevents infinite loops and duplicate loading

3. **Definition Processing**: 
   - Handles references to other definitions within the same file (`#/definitions/ModelName`)
   - Resolves external references to other files (`./otherFile.json#/definitions/ModelName`)
   - Implements comprehensive `allOf` inheritance support for OpenAPI schema composition
   - Detects and handles duplicate definition names across files
   - Recursively extracts inline enums from all property types (direct properties, array items, nested objects)
   - Maintains correct filename context for nested reference resolution

4. **AllOf Inheritance Processing**:
   - Processes `allOf` arrays to inherit properties from base types
   - Correctly handles external references within inherited definitions
   - Preserves filename context when resolving references in inherited schemas
   - Prevents duplicate field generation when inheritance and direct properties overlap
   - Supports multi-level inheritance chains

5. **Code Generation**:
   - Generates immutable Java records for complex data models in `com.azure.simpleSDK.models` package
   - Creates Java enums for both standalone enum definitions and inline enums with `@JsonValue` annotations
   - Handles reserved Java keywords by mapping them to safe field names
   - Adds source traceability comments showing the originating file and line number
   - Generates SDK client methods in `com.azure.simpleSDK.client` package

6. **Type Resolution**:
   - Maps OpenAPI types to appropriate Java types (string→String, integer→Integer/Long, etc.)
   - Handles generic collections (arrays become `List<T>`, objects with additionalProperties become `Map<String, T>`)
   - Resolves enum references and generates proper class names, handling duplicates with file prefixes

7. **Output**: Generated code is organized into packages:
   - Models: `sdk/src/main/java/com/azure/simpleSDK/models/`
   - HTTP Client: `sdk/src/main/java/com/azure/simpleSDK/client/`

## Key Technical Decisions

- **Java 17 HTTP Client**: Replaces complex async libraries with built-in synchronous HTTP support
- **Jackson ObjectMapper**: Standard JSON processing for Azure API responses with `@JsonProperty` and `@JsonValue` annotations
- **Immutable Records**: All model classes use Java records for immutability and thread safety
- **Source Traceability**: Every generated class includes comments showing originating OpenAPI file and line number
- **Recursive Enum Extraction**: Finds and generates enums from all property levels (direct, array items, nested objects)
- **Package Organization**: Models in `com.azure.simpleSDK.models`, client in `com.azure.simpleSDK.client`
- **Reserved Word Handling**: Java keywords are automatically mapped to safe alternatives (e.g., `default` → `dflt`)
- **Latest API Versions**: When multiple versions exist, automatically select the most recent stable version
- **Multi-module Build**: Separates code generation tool from generated SDK for clean distribution
- **Comprehensive Documentation**: Auto-generated Javadoc with OpenAPI operation metadata
- **External Reference Support**: Full resolution of cross-file and cross-directory references
- **AllOf Inheritance**: Complete support for OpenAPI schema composition and inheritance

## Implementation Details

### Core Components

- **SpecLoader** (`app/src/main/java/com/azure/simpleSDK/generator/SpecLoader.java`):
  - Scans OpenAPI specification files and extracts operations and definitions
  - Tracks line numbers using regex pattern matching for source traceability
  - Implements two-phase loading: initial files + external references
  - Recursively collects and loads external referenced files
  - Main entry point that orchestrates the entire generation process

- **JavaDefinitionGenerator** (`app/src/main/java/com/azure/simpleSDK/generator/JavaDefinitionGenerator.java`):
  - Converts OpenAPI definitions into Java code (records and enums)
  - Handles type resolution, reference resolution, and code formatting
  - Implements comprehensive `allOf` inheritance with proper context preservation
  - Recursively extracts inline enums from nested property structures
  - Uses `ResolvedDefinition` class to maintain filename context for references

- **OperationGenerator** (`app/src/main/java/com/azure/simpleSDK/generator/OperationGenerator.java`):
  - Generates SDK client methods from OpenAPI operations
  - Converts operation IDs to proper Java method names (handles compound verbs)
  - Extracts parameters from OpenAPI specifications
  - Generates comprehensive Javadoc with operation metadata
  - Creates type-safe method signatures with proper exception handling

- **DefinitionKey** (`app/src/main/java/com/azure/simpleSDK/generator/DefinitionKey.java`):
  - Immutable record storing filename, definition name, and line number
  - Used as a composite key for tracking definition sources

- **Operation** (`app/src/main/java/com/azure/simpleSDK/generator/Operation.java`):
  - Immutable record storing operation metadata (ID, path, method, spec)
  - Contains response schema mappings for type generation

### Advanced Features

#### External Reference Resolution

The system implements sophisticated external reference resolution:

```java
// SpecLoader.java - Two-phase loading
// Phase 1: Load initial files and collect external references
collectExternalReferences(rootNode, file.getParent(), externalRefsToLoad);

// Phase 2: Load external referenced files
loadExternalReferences(externalRefsToLoad, loadedFiles, definitions, mapper);
```

**Supported Reference Patterns**:
- Same-directory: `./networkManagerEffectiveConfiguration.json#/definitions/ConfigurationGroup`
- Cross-directory: `../../../../../common-types/resource-management/v5/types.json#/parameters/ApiVersionParameter`
- Recursive external references in loaded files

#### AllOf Inheritance Implementation

Handles OpenAPI schema composition with context preservation:

```java
// JavaDefinitionGenerator.java - AllOf processing with filename context
private static class ResolvedDefinition {
    private final JsonNode definition;
    private final String filename;  // Preserves context for nested references
}

private ResolvedDefinition resolveAllOfReference(String refValue, String currentFilename) {
    // Returns both definition and correct filename for context
}
```

**Key Features**:
- Prevents infinite recursion with visited reference tracking
- Maintains correct filename context for nested references
- Deduplicates fields when inheritance and direct properties overlap
- Supports multi-level inheritance chains

#### Method Name Generation

Converts OpenAPI operation IDs to proper Java method names:

```java
// OperationGenerator.java - Enhanced method naming
private String convertOperationIdToMethodName(String operationId) {
    // Handles compound verbs like "ListConnections" → "listConnections"
    String verb = verbPart.substring(0, 1).toLowerCase() + verbPart.substring(1);
    return verb + Character.toUpperCase(resource.charAt(0)) + resource.substring(1);
}
```

**Examples**:
- `VirtualNetworkGateways_Get` → `getVirtualNetworkGateways`
- `VirtualNetworkGateways_ListConnections` → `listConnectionsVirtualNetworkGateways`
- `VirtualNetworkGatewayConnections_List` → `listVirtualNetworkGatewayConnections`

### Generated Code Structure

All generated classes follow these patterns:

```java
// Records (for complex types)
package com.azure.simpleSDK.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

// Generated from filename.json:123
public record ModelName(
    String property1,
    @JsonProperty("property-name") String property2,
    List<EnumType> enumList
) {
}

// Enums (for enumeration types)
package com.azure.simpleSDK.models;

import com.fasterxml.jackson.annotation.JsonValue;

// Generated from filename.json:456
public enum EnumName {
    VALUE1("Value1"),
    VALUE2("Value2");

    private final String value;

    EnumName(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
```

## SDK Client Generation

The tool generates a comprehensive SDK client with the following features:

### Method Generation

- **Operation Filtering**: Only GET operations are currently generated for the client
- **Method Naming**: Converts OpenAPI operation IDs to proper camelCase Java method names
  - `VirtualNetworkGateways_List` → `listVirtualNetworkGateways`
  - `VirtualNetworkGateways_ListConnections` → `listConnectionsVirtualNetworkGateways`
- **Parameter Extraction**: Automatically extracts path and query parameters from OpenAPI specifications
- **Type Safety**: All methods use strongly typed parameters and return values

### Comprehensive Javadoc Documentation

Each generated method includes complete documentation:

```java
/**
 * Gets all the connections in a virtual network gateway.
 *
 * @apiNote Operation ID: VirtualNetworkGateways_ListConnections
 * @apiNote HTTP Method: GET
 * @apiNote URL: /subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}/providers/Microsoft.Network/virtualNetworkGateways/{virtualNetworkGatewayName}/connections
 * @param subscriptionId Parameter subscriptionId
 * @param resourceGroupName The name of the resource group.
 * @param virtualNetworkGatewayName The name of the virtual network gateway.
 * @return AzureResponse containing the operation result
 * @throws AzureException if the request fails
 */
public AzureResponse<VirtualNetworkGatewayListConnectionsResult> listConnectionsVirtualNetworkGateways(String subscriptionId, String resourceGroupName, String virtualNetworkGatewayName) throws AzureException {
    // Implementation
}
```

### Documentation Features

- **Operation Description**: Extracted from OpenAPI `description` field
- **OpenAPI Metadata**: Operation ID, HTTP method, and URL pattern
- **Parameter Documentation**: Individual descriptions for each parameter from OpenAPI spec
- **Return Type**: Strongly typed `AzureResponse<T>` with appropriate model class
- **Exception Handling**: Standard `AzureException` for all error scenarios
- **HTML Escaping**: Proper escaping of special characters for valid Javadoc

### Generated Statistics

Current generation produces:
- **1,095 Java record files** for data models
- **176 inline enum files** for enumeration types
- **346 GET operation methods** in the SDK client
- Complete compilation without errors
- Full external reference resolution

## HTTP Layer Design

The SDK includes a comprehensive HTTP layer built on Java 17's HTTP client for executing Azure REST API calls:

### Core HTTP Components

- **AzureHttpClient** (`com.azure.simpleSDK.http.AzureHttpClient`):
  - Main synchronous HTTP client wrapper around Java 17's HttpClient
  - Handles request execution, response processing, and error handling
  - Integrates authentication, retry logic, and Azure-specific headers
  - Automatically prepends Azure management endpoint (`https://management.azure.com`)
  - Configures Jackson ObjectMapper to ignore unknown JSON properties for resilient deserialization

- **AzureRequest** (`com.azure.simpleSDK.http.AzureRequest`):
  - Request builder with Azure-specific headers and authentication
  - Supports GET, POST, PUT, DELETE, PATCH operations
  - Automatic JSON serialization for request bodies

- **AzureResponse<T>** (`com.azure.simpleSDK.http.AzureResponse`):
  - Generic response wrapper with automatic deserialization
  - Provides access to status code, headers, and typed response body
  - Handles both JSON and binary response types

### Authentication Support

The HTTP layer supports multiple Azure authentication methods:

- **Service Principal**: Client credentials flow with client ID/secret
- **Bearer Tokens**: Direct token authentication

**Authentication Interface**:
```java
public interface AzureCredentials {
    String getAccessToken() throws AzureAuthenticationException;
    boolean isExpired();
    void refresh() throws AzureAuthenticationException;
}
```

**Service Principal Implementation**:
- Handles OAuth2 client credentials flow with Azure AD
- Automatically manages token refresh and expiration
- Resilient token response parsing that ignores extra fields (`token_type`, `ext_expires_in`)
- Thread-safe token caching with proper synchronization

### Error Handling

Comprehensive exception hierarchy for Azure-specific errors:

- **AzureException** - Base exception for all Azure SDK errors
- **AzureServiceException** - HTTP 4xx/5xx responses with Azure error details
- **AzureNetworkException** - Network connectivity and timeout issues  
- **AzureAuthenticationException** - Authentication failures (401/403)
- **AzureResourceNotFoundException** - Resource not found errors (404)

Azure error responses are parsed and mapped to structured exceptions:
```java
{
  "error": {
    "code": "InvalidResourceName",
    "message": "Resource name is invalid", 
    "details": [...],
    "innererror": {...}
  }
}
```

### Retry and Resilience

Built-in retry mechanism with exponential backoff:

- **Retry Strategy**: Exponential backoff (100ms → 1600ms, max 5 attempts)
- **Retryable Conditions**: Network errors, 502/503/504, timeouts, 429 rate limiting
- **Non-retryable**: 4xx client errors (except 429)
- **Respect Headers**: Honors `Retry-After` header when present

### Azure-Specific Headers

Automatic handling of standard Azure headers:

- `Authorization: Bearer {token}` - Authentication
- `x-ms-version: {apiVersion}` - API versioning  
- `x-ms-client-request-id: {guid}` - Request correlation
- `User-Agent: azure-simple-sdk/{version}` - Client identification
- `Content-Type: application/json` - JSON content type
- `Accept: application/json` - Expected response format

### Usage Pattern

The generated SDK client provides a high-level interface:

```java
// Create credentials and client
AzureCredentials credentials = new ServicePrincipalCredentials(clientId, clientSecret, tenantId);
AzureSimpleSDKClient client = new AzureSimpleSDKClient(credentials);

// Call generated methods with full type safety and documentation
AzureResponse<VirtualNetworkGatewayListConnectionsResult> response = client
    .listConnectionsVirtualNetworkGateways(subscriptionId, resourceGroupName, gatewayName);

VirtualNetworkGatewayListConnectionsResult connections = response.getBody();
```

**Low-level HTTP client usage** (used internally by generated methods):

```java
AzureHttpClient httpClient = new AzureHttpClient(credentials);
AzureResponse<VirtualNetworkGateway> response = httpClient
    .get("/subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}/providers/Microsoft.Network/virtualNetworkGateways/{gatewayName}")
    .version("2024-07-01")
    .execute(VirtualNetworkGateway.class);
```

### Package Structure

```
com.azure.simpleSDK.http/
├── AzureHttpClient.java          # Main HTTP client
├── AzureRequest.java             # Request builder
├── AzureResponse.java            # Response wrapper
├── auth/
│   ├── AzureCredentials.java     # Authentication interface
│   ├── ServicePrincipalCredentials.java
│   └── ManagedIdentityCredentials.java
├── exceptions/
│   ├── AzureException.java       # Base exception
│   ├── AzureServiceException.java
│   ├── AzureNetworkException.java
│   └── AzureAuthenticationException.java
└── retry/
    ├── RetryPolicy.java          # Retry configuration
    └── ExponentialBackoffStrategy.java
```

## Demo Application

The project includes a comprehensive demo application that demonstrates how to use the generated Azure Simple SDK to authenticate with Azure and interact with Azure resources.

### Demo Features

- **Service Principal Authentication**: Loads Azure credentials from a local properties file
- **Azure API Integration**: Demonstrates real Azure API calls using the generated SDK  
- **Firewall Enumeration**: Fetches and displays all Azure Firewalls in a subscription
- **JSON Output**: Shows both structured data and raw JSON responses
- **Error Handling**: Comprehensive error handling with helpful error messages

### Demo Structure

```
demo/
├── src/main/java/com/azure/simpleSDK/demo/
│   └── DemoApplication.java         # Main demo application
├── azure.properties.template        # Template for Azure credentials
├── build.gradle                     # Demo module build configuration
└── README.md                        # Complete demo documentation
```

### Running the Demo

1. **Setup Credentials**: Copy `azure.properties.template` to `azure.properties` and fill in your Azure Service Principal credentials:
   ```properties
   azure.tenant-id=YOUR_TENANT_ID_HERE
   azure.client-id=YOUR_CLIENT_ID_HERE  
   azure.client-secret=YOUR_CLIENT_SECRET_HERE
   azure.subscription-id=YOUR_SUBSCRIPTION_ID_HERE
   ```

2. **Run the Demo**: Execute from the project root:
   ```bash
   ./gradlew :demo:run
   ```

### Demo Output Example

```
Fetching Azure Firewalls for subscription: 12345678-1234-1234-1234-123456789abc
==========================================
Response Status: 200
Number of firewalls found: 1

Azure Firewalls:
================
Firewall Name: my-firewall
Resource Group: my-resource-group
Location: westeurope
Provisioning State: Succeeded
ID: /subscriptions/.../resourceGroups/my-rg/providers/Microsoft.Network/azureFirewalls/my-firewall

Full JSON Response:
==================
{ "value": [...] }
```

### Demo Technical Details

The demo application showcases:

- **Authentication Flow**: Service Principal credential creation and token acquisition
- **SDK Client Usage**: Creating `AzureSimpleSDKClient` with proper credentials
- **API Method Calls**: Using generated methods like `listAllAzureFirewalls(subscriptionId)`
- **Response Processing**: Accessing typed response data and raw JSON
- **Resource Parsing**: Extracting resource group names from Azure resource IDs
- **Error Handling**: Graceful handling of authentication and network errors

## Azure API Specifications

The azure-rest-api-specs directory contains the complete Microsoft Azure REST API specification collection. Key locations:

- Network APIs: `azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/`
- Stable versions in `/stable/YYYY-MM-DD/` directories
- Preview versions in `/preview/YYYY-MM-DD-preview/` directories
- Each service has multiple JSON files for different resource types (e.g., virtualNetworkGateway.json)

## Current Status and Capabilities

### What's Working

✅ **Complete Model Generation**: 1,095 Java records with full inheritance support  
✅ **External Reference Resolution**: Cross-file and cross-directory references  
✅ **AllOf Inheritance**: Full OpenAPI schema composition support  
✅ **Enum Generation**: 176 standalone and inline enums with proper Jackson annotations  
✅ **SDK Client Generation**: 346 type-safe GET operation methods  
✅ **Comprehensive Documentation**: Auto-generated Javadoc with OpenAPI metadata  
✅ **HTTP Layer**: Complete Azure authentication, retry, and error handling  
✅ **Demo Application**: Working demonstration with real Azure API calls  
✅ **Production Ready**: Zero compilation errors, tested with live Azure APIs  

### Current Limitations

⏳ **Operation Support**: Only GET operations currently implemented  
⏳ **API Coverage**: Limited to Azure Network APIs (2024-07-01)  
⏳ **Authentication**: Service Principal and Bearer token only  

### Architecture Highlights

- **Type Safety**: All generated code uses strong typing with compile-time validation
- **Immutability**: Java records ensure thread-safe, immutable data models
- **Traceability**: Every generated class links back to source OpenAPI definition
- **Maintainability**: Clean separation between generation tool and SDK output
- **Extensibility**: Modular design supports adding new Azure services and operations
- **Standards Compliance**: Follows OpenAPI 3.0 specification and Java coding conventions

The system successfully handles the complexity of Azure's OpenAPI specifications while generating clean, type-safe, and well-documented Java code suitable for production use.
