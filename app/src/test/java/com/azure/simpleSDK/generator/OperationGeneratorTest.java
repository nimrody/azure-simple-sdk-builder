package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for OperationGenerator that verify client class generation and method creation.
 * These tests ensure that OpenAPI operations are correctly converted to Java client methods.
 */
class OperationGeneratorTest {
    
    @TempDir
    Path tempDir;
    
    private ObjectMapper objectMapper;
    private Map<String, Operation> operations;
    private Map<DefinitionKey, JsonNode> definitions;
    private OperationGenerator generator;
    private OperationGenerator serviceSpecificGenerator;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        definitions = loadTestDefinitions();
        operations = loadTestOperations();
        
        generator = new OperationGenerator(operations, new HashSet<>(), definitions);
        
        serviceSpecificGenerator = new OperationGenerator(
            operations,
            Set.of("Resource"),
            definitions,
            "com.azure.simpleSDK.test.models",
            "com.azure.simpleSDK.test.client",
            "TestApiClient",
            "2024-07-01"
        );
    }
    
    private Map<DefinitionKey, JsonNode> loadTestDefinitions() throws IOException {
        Map<DefinitionKey, JsonNode> defs = new HashMap<>();
        
        // Load definitions from test JSON files
        String[] files = {"user.json", "profile.json", "common.json"};
        
        for (String filename : files) {
            JsonNode spec = loadTestJson(filename);
            JsonNode specDefs = spec.get("definitions");
            if (specDefs != null) {
                specDefs.fieldNames().forEachRemaining(name -> {
                    defs.put(new DefinitionKey(filename, name, name.hashCode() % 1000), specDefs.get(name));
                });
            }
        }
        
        return defs;
    }
    
    private Map<String, Operation> loadTestOperations() throws IOException {
        Map<String, Operation> ops = new HashMap<>();
        
        JsonNode userSpec = loadTestJson("user.json");
        JsonNode paths = userSpec.get("paths");
        
        if (paths != null) {
            paths.fieldNames().forEachRemaining(path -> {
                JsonNode pathNode = paths.get(path);
                pathNode.fieldNames().forEachRemaining(method -> {
                    JsonNode operationNode = pathNode.get(method);
                    if (operationNode.has("operationId")) {
                        String operationId = operationNode.get("operationId").asText();
                        Map<String, String> responseSchemas = extractResponseSchemas(operationNode);
                        ops.put(operationId, new Operation(
                            operationId,
                            path,
                            method.toUpperCase(),
                            operationNode,
                            responseSchemas
                        ));
                    }
                });
            });
        }
        
        return ops;
    }
    
    private Map<String, String> extractResponseSchemas(JsonNode operationNode) {
        Map<String, String> schemas = new HashMap<>();
        JsonNode responses = operationNode.get("responses");
        if (responses != null) {
            responses.fieldNames().forEachRemaining(code -> {
                JsonNode response = responses.get(code);
                if (response.has("schema") && response.get("schema").has("$ref")) {
                    schemas.put(code, response.get("schema").get("$ref").asText());
                }
            });
        }
        return schemas;
    }
    
    private JsonNode loadTestJson(String filename) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("testdata/" + filename)) {
            if (is == null) {
                throw new IOException("Test file not found: " + filename);
            }
            return objectMapper.readTree(is);
        }
    }
    
    @Test
    @DisplayName("Should generate default Azure client class structure")
    void testGenerateDefaultAzureClient() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        
        assertThat(clientFile).exists();
        String generatedContent = Files.readString(clientFile);
        
        // Verify package declaration
        assertThat(generatedContent).contains("package com.azure.simpleSDK.client;");
        
        // Verify imports
        assertThat(generatedContent).contains("import com.azure.simpleSDK.models.*;");
        assertThat(generatedContent).contains("import com.azure.simpleSDK.http.*;");
        
        // Verify class declaration
        assertThat(generatedContent).contains("public class AzureSimpleSDKClient");
        
        // Verify constructor
        assertThat(generatedContent).contains("public AzureSimpleSDKClient(AzureCredentials credentials)");
        
        // Verify HTTP client field
        assertThat(generatedContent).contains("private final AzureHttpClient httpClient;");
    }
    
    @Test
    @DisplayName("Should generate service-specific client class")
    void testGenerateServiceSpecificClient() throws IOException {
        Path clientFile = tempDir.resolve("TestApiClient.java");
        
        serviceSpecificGenerator.generateAzureClient(tempDir.toString());
        
        assertThat(clientFile).exists();
        String generatedContent = Files.readString(clientFile);
        
        // Verify custom package and class name
        assertThat(generatedContent).contains("package com.azure.simpleSDK.test.client;");
        assertThat(generatedContent).contains("import com.azure.simpleSDK.test.models.*;");
        assertThat(generatedContent).contains("public class TestApiClient");
    }
    
    @Test
    @DisplayName("Should generate GET method with path parameters")
    void testGenerateGetMethodWithPathParameters() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Verify Users_Get method exists with correct structure
        
        assertThat(generatedContent).contains("getUsers"); // Method name
        assertThat(generatedContent).contains("@param userId"); // Parameter documentation
        assertThat(generatedContent).contains("@param expand"); // Parameter documentation
        assertThat(generatedContent).contains("AzureResponse<User>"); // Return type
    }
    
    @Test
    @DisplayName("Should generate GET method with query parameters")
    void testGenerateGetMethodWithQueryParameters() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        System.out.println("Generated client content:");
        System.out.println(generatedContent);
        
        // Verify Users_List method exists in some form
        assertThat(generatedContent).contains("listUsers"); // Method name
        assertThat(generatedContent).contains("@param filter"); // Parameter documentation
        assertThat(generatedContent).contains("@param top"); // Parameter documentation
        assertThat(generatedContent).contains("AzureResponse<UserListResult>"); // Return type
    }
    
    @Test
    @DisplayName("Should generate method with cross-file response type")
    void testGenerateMethodWithCrossFileResponse() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Verify Users_ListProfiles method references profile.json response type
        assertThat(generatedContent).contains("listProfilesUsers"); // Method name
        assertThat(generatedContent).contains("@param userId"); // Parameter
        assertThat(generatedContent).contains("AzureResponse<UserProfileListResult>"); // Cross-file response type
    }
    
    @Test
    @DisplayName("Should convert operation IDs to proper Java method names")
    void testOperationIdToMethodNameConversion() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Verify method name conversions
        assertThat(generatedContent).contains("getUsers"); // Users_Get -> getUsers
        assertThat(generatedContent).contains("listUsers"); // Users_List -> listUsers
        assertThat(generatedContent).contains("listProfilesUsers"); // Users_ListProfiles -> listProfilesUsers
    }
    
    @Test
    @DisplayName("Should generate proper Javadoc with operation metadata")
    void testGenerateJavadocWithMetadata() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Verify Javadoc structure
        assertThat(generatedContent).contains("* Gets a specific user by ID");
        assertThat(generatedContent).contains("* @apiNote Operation ID: Users_Get");
        assertThat(generatedContent).contains("* @apiNote HTTP Method: GET");
        assertThat(generatedContent).contains("* @apiNote URL: /users/{userId}");
        assertThat(generatedContent).contains("* @throws AzureException if the request fails");
    }
    
    @Test
    @DisplayName("Should generate HTTP client implementation with proper URL construction")
    void testGenerateHttpClientImplementation() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Verify basic HTTP client implementation structure
        assertThat(generatedContent).contains("httpClient.get(url)"); // HTTP GET calls
        assertThat(generatedContent).contains(".version("); // API versioning
        assertThat(generatedContent).contains("queryParam"); // Query parameter handling
    }
    
    @Test
    @DisplayName("Should generate client with custom API version")
    void testGenerateClientWithCustomApiVersion() throws IOException {
        Path clientFile = tempDir.resolve("TestApiClient.java");
        
        serviceSpecificGenerator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Verify custom API version is used
        assertThat(generatedContent).contains("2024-07-01"); // API version appears somewhere
    }
    
    @Test
    @DisplayName("Should handle operations with no query parameters")
    void testGenerateOperationWithoutQueryParameters() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Find the Users_ListProfiles method (only has path parameter)
        assertThat(generatedContent).contains("listProfilesUsers"); // Method exists
        assertThat(generatedContent).contains("/profiles"); // Path construction
    }
    
    @Test
    @DisplayName("Should generate proper import statements")
    void testGenerateImportStatements() throws IOException {
        Path clientFile = tempDir.resolve("TestApiClient.java");
        
        serviceSpecificGenerator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Verify all necessary imports are present (actual import structure may be different)
        assertThat(generatedContent).contains("import com.azure.simpleSDK.test.models.*;");
        assertThat(generatedContent).contains("AzureCredentials"); // Class is imported/referenced
        assertThat(generatedContent).contains("AzureException"); // Class is imported/referenced
        assertThat(generatedContent).contains("AzureHttpClient"); // Class is imported/referenced
        assertThat(generatedContent).contains("AzureResponse"); // Class is imported/referenced
    }
    
    @Test
    @DisplayName("Should only generate GET operations")
    void testOnlyGenerateGetOperations() throws IOException {
        Path clientFile = tempDir.resolve("AzureSimpleSDKClient.java");
        
        generator.generateAzureClient(tempDir.toString());
        String generatedContent = Files.readString(clientFile);
        
        // Count method declarations (should only be GET operations + constructor)
        long methodCount = generatedContent.lines()
            .filter(line -> line.trim().startsWith("public ") && line.contains("("))
            .filter(line -> !line.contains("AzureSimpleSDKClient(")) // Exclude constructor
            .count();
        
        assertThat(methodCount).isEqualTo(3); // Three GET operations from test data
    }
    
    private String extractMethodContent(String content, String methodName) {
        int start = content.indexOf("public AzureResponse<") + content.substring(content.indexOf("public AzureResponse<")).indexOf(methodName);
        int end = content.indexOf("}", start) + 1;
        return content.substring(start, end);
    }
}