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

This is a multi-module Gradle project with two main components:

- **app/** - Main application module containing the code generation tool

- **sdk/** - Generated SDK module that will contain the final Azure Simple SDK
  library

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

1. **Specification Loading**: Scans all JSON files in the azure-rest-api-specs directory and extracts:
   - Operations from the `paths` section
   - Model definitions from the `definitions` section
   - Records the file name and line number where each definition appears for traceability

2. **Definition Processing**: 
   - Handles references to other definitions within the same file (`#/definitions/ModelName`)
   - Resolves external references to other files (`./otherFile.json#/definitions/ModelName`)
   - Detects and handles duplicate definition names across files
   - Recursively extracts inline enums from all property types (direct properties, array items, nested objects)

3. **Code Generation**:
   - Generates immutable Java records for complex data models in `com.azure.simpleSDK.models` package
   - Creates Java enums for both standalone enum definitions and inline enums with `@JsonValue` annotations
   - Handles reserved Java keywords by mapping them to safe field names
   - Adds source traceability comments showing the originating file and line number

4. **Type Resolution**:
   - Maps OpenAPI types to appropriate Java types (string→String, integer→Integer/Long, etc.)
   - Handles generic collections (arrays become `List<T>`, objects with additionalProperties become `Map<String, T>`)
   - Resolves enum references and generates proper class names, handling duplicates with file prefixes

5. **Output**: All generated code is placed in the `sdk/src/main/java/com/azure/simpleSDK/models/` directory

## Key Technical Decisions

- **Java 17 HTTP Client**: Replaces complex async libraries with built-in synchronous HTTP support
- **Jackson ObjectMapper**: Standard JSON processing for Azure API responses with `@JsonProperty` and `@JsonValue` annotations
- **Immutable Records**: All model classes use Java records for immutability and thread safety
- **Source Traceability**: Every generated class includes comments showing originating OpenAPI file and line number
- **Recursive Enum Extraction**: Finds and generates enums from all property levels (direct, array items, nested objects)
- **Package Organization**: All generated models are placed in `com.azure.simpleSDK.models` package
- **Reserved Word Handling**: Java keywords are automatically mapped to safe alternatives (e.g., `default` → `dflt`)
- **Latest API Versions**: When multiple versions exist, automatically select the most recent stable version
- **Multi-module Build**: Separates code generation tool from generated SDK for clean distribution

## Implementation Details

### Core Components

- **SpecLoader** (`app/src/main/java/com/azure/simpleSDK/generator/SpecLoader.java`):
  - Scans OpenAPI specification files and extracts operations and definitions
  - Tracks line numbers using regex pattern matching for source traceability
  - Main entry point that orchestrates the entire generation process

- **JavaDefinitionGenerator** (`app/src/main/java/com/azure/simpleSDK/generator/JavaDefinitionGenerator.java`):
  - Converts OpenAPI definitions into Java code (records and enums)
  - Handles type resolution, reference resolution, and code formatting
  - Recursively extracts inline enums from nested property structures

- **DefinitionKey** (`app/src/main/java/com/azure/simpleSDK/generator/DefinitionKey.java`):
  - Immutable record storing filename, definition name, and line number
  - Used as a composite key for tracking definition sources

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

## HTTP Layer Design

The SDK includes a comprehensive HTTP layer built on Java 17's HTTP client for executing Azure REST API calls:

### Core HTTP Components

- **AzureHttpClient** (`com.azure.simpleSDK.http.AzureHttpClient`):
  - Main synchronous HTTP client wrapper around Java 17's HttpClient
  - Handles request execution, response processing, and error handling
  - Integrates authentication, retry logic, and Azure-specific headers

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

Generated SDK operations use the HTTP layer like this:

```java
AzureHttpClient client = new AzureHttpClient(credentials);
AzureResponse<VirtualNetworkGateway> response = client
    .get("/subscriptions/{subscriptionId}/resourceGroups/{resourceGroupName}/providers/Microsoft.Network/virtualNetworkGateways/{gatewayName}")
    .version("2024-07-01")
    .execute(VirtualNetworkGateway.class);

VirtualNetworkGateway gateway = response.getBody();
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

## Azure API Specifications

The azure-rest-api-specs directory contains the complete Microsoft Azure REST API specification collection. Key locations:

- Network APIs: `azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/`
- Stable versions in `/stable/YYYY-MM-DD/` directories
- Preview versions in `/preview/YYYY-MM-DD-preview/` directories
- Each service has multiple JSON files for different resource types (e.g., virtualNetworkGateway.json)
