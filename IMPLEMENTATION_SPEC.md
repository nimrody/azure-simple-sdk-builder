# Azure Simple SDK Builder - Implementation Specification

## Overview

This specification outlines the implementation approach for building a code generation tool that creates a simplified Azure SDK. The tool parses OpenAPI/Swagger specifications from the Azure REST API specs repository and generates Java code using Java 17's built-in HTTP client, Jackson for JSON processing, and immutable records for data models.

## Architecture

### 1. Core Components

#### 1.1 Main Application (`app` module)
- **Entry Point**: Command-line application that accepts operation names (e.g., `VirtualNetworkGateways_List`)
- **Specification Finder**: Locates relevant JSON specification files in the azure-rest-api-specs directory
- **Parser Engine**: Processes OpenAPI/Swagger JSON to extract operation and model definitions
- **Code Generator**: Generates Java source files for models and REST clients
- **Build Integrator**: Updates the SDK module with generated code

#### 1.2 Generated SDK (`sdk` module)
- **Model Classes**: Immutable Java records for all data transfer objects
- **Client Classes**: REST client implementations using Java 17 HTTP client
- **Configuration**: Authentication and endpoint configuration
- **Exception Handling**: Azure-specific error handling

### 2. Data Flow

```
CLI Input (Operation Names) 
    ↓
Specification Discovery
    ↓
OpenAPI Parsing & Model Extraction
    ↓
Code Generation (Models + Clients)
    ↓
SDK Module Update
    ↓
Build & Package SDK JAR
```

## Implementation Details

### 3. Specification Discovery

#### 3.1 Operation Resolution Strategy
```java
public class SpecificationFinder {
    // Find the JSON file containing the specified operation
    // Priority: Latest stable version > Latest preview version
    public SpecificationFile findOperationSpec(String operationId) {
        // 1. Search all JSON files under azure-rest-api-specs/specification/
        // 2. Parse JSON to find matching operationId
        // 3. Return file with latest date in path if multiple matches
    }
}
```

#### 3.2 Version Resolution
- Parse directory structure: `/stable/YYYY-MM-DD/` and `/preview/YYYY-MM-DD-preview/`
- Prefer stable over preview versions
- Select latest date when multiple versions contain the same model
- Cache specification file metadata for performance

### 4. OpenAPI Parsing

#### 4.1 Key Data Structures
```java
public record OperationSpec(
    String operationId,
    String httpMethod,
    String path,
    List<Parameter> parameters,
    Map<String, Response> responses,
    String description
) {}

public record ModelDefinition(
    String name,
    String type,
    Map<String, Property> properties,
    List<String> required,
    String description
) {}
```

#### 4.2 Reference Resolution
- Resolve `$ref` references to external definitions
- Handle references to `./network.json#/definitions/CloudError` format
- Build complete model dependency graph
- Detect circular dependencies and handle appropriately

### 5. Model Generation

#### 5.1 Java Record Generation
```java
// Generated from OpenAPI definition
public record VirtualNetworkGateway(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("location") String location,
    @JsonProperty("tags") Map<String, String> tags,
    @JsonProperty("properties") VirtualNetworkGatewayProperties properties,
    @JsonProperty("etag") String etag
) {
    // Jackson configuration for proper deserialization
}
```

#### 5.2 Type Mapping
- OpenAPI `string` → Java `String`
- OpenAPI `integer` → Java `Integer/Long` based on format
- OpenAPI `boolean` → Java `Boolean`
- OpenAPI `array` → Java `List<T>`
- OpenAPI `object` → Java `Map<String, Object>` or custom record
- Handle nullable fields with appropriate wrappers

#### 5.3 Validation and Constraints
- Generate validation annotations from OpenAPI constraints
- Handle required fields in record constructors
- Add format validation for dates, emails, etc.

### 6. HTTP Client Generation

#### 6.1 Client Class Structure
```java
public class VirtualNetworkGatewayClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final TokenCredential credential;

    public VirtualNetworkGatewayClient(TokenCredential credential) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.credential = credential;
        this.baseUrl = "https://management.azure.com";
    }

    public List<VirtualNetworkGateway> list(String subscriptionId, String resourceGroupName) {
        // Generated method implementation
    }
}
```

#### 6.2 Method Generation Strategy
- Generate one method per OpenAPI operation
- Map path parameters to method parameters
- Handle query parameters in URL building
- Process request/response body marshalling
- Implement proper error handling

#### 6.3 Authentication Integration
```java
private String getAccessToken() {
    // Use Azure Identity library for token acquisition
    return credential.getToken(new TokenRequestContext()
        .addScopes("https://management.azure.com/.default"))
        .block().getToken();
}
```

### 7. Error Handling

#### 7.1 Azure Error Response Processing
```java
public class AzureException extends RuntimeException {
    private final String errorCode;
    private final String errorMessage;
    private final int statusCode;
    
    // Generated from CloudError definition
}
```

#### 7.2 HTTP Status Code Mapping
- 200/201: Success responses with proper deserialization
- 202: Long-running operations with polling support
- 404: Resource not found exceptions
- 400/401/403: Client error exceptions
- 500+: Server error exceptions

### 8. Build System Integration

#### 8.1 Gradle Configuration
```groovy
// app/build.gradle
plugins {
    id 'java'
    id 'application'
}

dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
    // ... other dependencies
}

// Generate task
task generateSdk(type: JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.azure.simpleSDK.generator.Main'
    args = project.property('operations').split(',')
}
```

#### 8.2 SDK Module Configuration
```groovy
// sdk/build.gradle
plugins {
    id 'java-library'
}

dependencies {
    api 'com.azure:azure-identity'
    api 'com.fasterxml.jackson.core:jackson-databind'
    // ... runtime dependencies only
}
```

### 9. Code Organization

#### 9.1 Package Structure
```
com.azure.simpleSDK.generator/          # Code generation tool
├── cli/                               # Command line interface
├── parser/                            # OpenAPI parsing
├── generator/                         # Code generation
└── utils/                             # Utilities

com.azure.simpleSDK/                   # Generated SDK
├── models/                            # Generated model records
├── clients/                           # Generated REST clients
├── auth/                             # Authentication helpers
└── exceptions/                        # Azure exception classes
```

#### 9.2 Naming Conventions
- Model classes: PascalCase matching OpenAPI definition names
- Client classes: `{ResourceType}Client` (e.g., `VirtualNetworkGatewayClient`)
- Methods: camelCase matching operation suffixes (e.g., `list()`, `get()`, `createOrUpdate()`)

### 10. Advanced Features

#### 10.1 Long-Running Operations
```java
public class LongRunningOperation<T> {
    public T waitForCompletion() {
        // Poll operation status until complete
        // Return final result
    }
    
    public CompletableFuture<T> getResultAsync() {
        // Async polling implementation
    }
}
```

#### 10.2 Pagination Support
```java
public class PagedIterable<T> implements Iterable<T> {
    // Handle Azure pagination patterns
    // Support both sync and async iteration
}
```

#### 10.3 Configuration Management
```java
public record AzureConfiguration(
    String subscriptionId,
    String baseUrl,
    Duration timeout,
    RetryPolicy retryPolicy
) {
    public static AzureConfiguration defaultConfig() {
        // Default Azure configuration
    }
}
```

## Implementation Phases

### Phase 1: Foundation (Week 1-2)
1. Set up Gradle multi-module project structure
2. Implement basic CLI argument parsing
3. Create OpenAPI JSON parsing infrastructure
4. Build specification discovery mechanism

### Phase 2: Core Generation (Week 3-4)
1. Implement model record generation
2. Create basic HTTP client generation
3. Add Jackson serialization configuration
4. Implement reference resolution

### Phase 3: Client Enhancement (Week 5-6)
1. Add authentication integration
2. Implement error handling
3. Create long-running operation support
4. Add pagination handling

### Phase 4: Polish & Testing (Week 7-8)
1. Comprehensive testing with real Azure APIs
2. Performance optimization
3. Documentation generation
4. CI/CD pipeline setup

## Quality Assurance

### Testing Strategy
- Unit tests for each component
- Integration tests with real Azure API specs
- Generated code compilation verification
- Runtime testing against Azure sandbox

### Code Quality
- Checkstyle configuration for consistent formatting
- SpotBugs for static analysis
- SonarQube for code quality metrics
- Automated dependency vulnerability scanning

## Success Metrics
- Successfully generate client code for top 50 Azure operations
- Generated SDK size < 10MB
- Compilation time < 30 seconds for complete SDK
- Runtime performance comparable to official Azure SDK
- Zero critical security vulnerabilities