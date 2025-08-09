# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This is a Java command line tool to create a library to replace the broken Azure SDK. 

The tool will read specifications from the folder azure-rest-api-spects/specification and generate the necessary model files and code to call the relevant REST API endpoint. 

The tool accepts a list of API calls to generate code for. E.g., specifying `VirtualNetworkGateways_List` will find the endpoint at azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/stable/2024-07-01/virtualNetworkGateway.json and create models and code for this endpoint.

## Features

* Instead of relying on the Reactor async library, the generated code is using the built Java 17 HTTP synchronous client. 

* The generated code parses Azure JSON responses using Jackson ObjectMapper. All models created from the HTTP response data will use immutable Java records.

* When the same model appears in multiple files and paths contain date, use the file where the date is latest.

* Built with Gradle and requires Java 17+

## Project Structure

This is a multi-module Gradle project with two main components:

- **app/** - Main application module containing the code generation tool
- **sdk/** - Generated SDK module that will contain the final Azure Simple SDK library
- **azure-rest-api-specs/** - Git submodule containing Microsoft Azure REST API specifications

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

1. Locates the specified operation (e.g., `VirtualNetworkGateways_List`) in the appropriate JSON specification files
2. Parses the OpenAPI/Swagger definitions to extract models and endpoints
3. Generates immutable Java records for all data models
4. Creates REST client code using Java 17's built-in HTTP client
5. Uses Jackson for JSON serialization/deserialization
6. Outputs all generated code to the sdk/ module

## Key Technical Decisions

- **Java 17 HTTP Client**: Replaces complex async libraries with built-in synchronous HTTP support
- **Jackson ObjectMapper**: Standard JSON processing for Azure API responses
- **Immutable Records**: All model classes use Java records for immutability
- **Latest API Versions**: When multiple versions exist, automatically select the most recent stable version
- **Multi-module Build**: Separates code generation tool from generated SDK for clean distribution

## Azure API Specifications

The azure-rest-api-specs directory contains the complete Microsoft Azure REST API specification collection. Key locations:

- Network APIs: `azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/`
- Stable versions in `/stable/YYYY-MM-DD/` directories
- Preview versions in `/preview/YYYY-MM-DD-preview/` directories
- Each service has multiple JSON files for different resource types (e.g., virtualNetworkGateway.json)
