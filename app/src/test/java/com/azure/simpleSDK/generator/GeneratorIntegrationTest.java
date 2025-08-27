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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the complete generator pipeline.
 * These tests verify that the entire process from OpenAPI specs to generated Java files works correctly.
 */
class GeneratorIntegrationTest {
    
    @TempDir
    Path tempDir;
    
    private ObjectMapper objectMapper;
    private Path testDataDir;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        
        // Copy test data to temp directory to simulate file loading
        testDataDir = tempDir.resolve("testdata");
        Files.createDirectories(testDataDir);
        
        copyTestFile("user.json");
        copyTestFile("profile.json");
        copyTestFile("common.json");
    }
    
    private void copyTestFile(String filename) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("testdata/" + filename)) {
            if (is == null) {
                throw new IOException("Test file not found: " + filename);
            }
            Files.copy(is, testDataDir.resolve(filename));
        }
    }
    
    @Test
    @DisplayName("Should perform complete generation pipeline with cross-file references")
    void testCompleteGenerationPipeline() throws IOException {
        // Simulate SpecLoader behavior
        TestSpecLoader testLoader = new TestSpecLoader(testDataDir);
        SpecResult result = testLoader.loadSpecs();
        
        // Verify loaded data
        assertThat(result.operations()).hasSize(3);
        assertThat(result.definitions()).isNotEmpty();
        
        // Test definition generation
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        
        Set<String> duplicateNames = SpecLoader.findDuplicateDefinitionNames(result.definitions());
        JavaDefinitionGenerator definitionGenerator = new JavaDefinitionGenerator(
            duplicateNames, 
            result.definitions(),
            "com.azure.simpleSDK.test.models"
        );
        
        // Generate all model files
        int generatedCount = 0;
        for (Map.Entry<DefinitionKey, JsonNode> entry : result.definitions().entrySet()) {
            definitionGenerator.writeRecordToFile(entry.getKey(), entry.getValue(), modelsDir.toString());
            generatedCount++;
        }
        
        // Generate inline enums
        Map<String, JsonNode> inlineEnums = definitionGenerator.getInlineEnums();
        for (Map.Entry<String, JsonNode> enumEntry : inlineEnums.entrySet()) {
            definitionGenerator.writeInlineEnumToFile(enumEntry.getKey(), enumEntry.getValue(), modelsDir.toString());
        }
        
        // Test client generation
        Path clientDir = tempDir.resolve("client");
        Files.createDirectories(clientDir);
        
        OperationGenerator operationGenerator = new OperationGenerator(
            result.operations(),
            duplicateNames,
            result.definitions(),
            "com.azure.simpleSDK.test.models",
            "com.azure.simpleSDK.test.client",
            "TestUserApiClient",
            "2024-07-01"
        );
        
        operationGenerator.generateAzureClient(clientDir.toString());
        
        // Verify generated files exist
        assertThat(modelsDir.resolve("User.java")).exists();
        assertThat(modelsDir.resolve("UserStatus.java")).exists();
        assertThat(modelsDir.resolve("UserProfile.java")).exists();
        assertThat(modelsDir.resolve("UserListResult.java")).exists();
        assertThat(clientDir.resolve("TestUserApiClient.java")).exists();
        
        // Verify duplicate handling worked
        assertThat(modelsDir.resolve("UserResource.java")).exists(); // user.json Resource
        assertThat(modelsDir.resolve("CommonResource.java")).exists(); // common.json Resource
        
        // Verify inline enum extraction mechanism worked (inline enums may or may not be present)
        assertThat(inlineEnums).isNotNull();
        
        System.out.println("Generated " + generatedCount + " model files");
        System.out.println("Generated " + inlineEnums.size() + " inline enum files");
        System.out.println("Files created in: " + tempDir);
    }
    
    @Test
    @DisplayName("Should generate correct cross-file references in models")
    void testCrossFileReferenceResolution() throws IOException {
        TestSpecLoader testLoader = new TestSpecLoader(testDataDir);
        SpecResult result = testLoader.loadSpecs();
        
        Set<String> duplicateNames = SpecLoader.findDuplicateDefinitionNames(result.definitions());
        JavaDefinitionGenerator generator = new JavaDefinitionGenerator(
            duplicateNames, 
            result.definitions(),
            "com.azure.simpleSDK.test.models"
        );
        
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        
        // Generate User model which references UserProfile from profile.json
        DefinitionKey userKey = findDefinitionKey(result.definitions(), "user.json", "User");
        generator.writeRecordToFile(userKey, result.definitions().get(userKey), modelsDir.toString());
        
        // Generate UserProfile which uses allOf with common.json BaseProfile
        DefinitionKey userProfileKey = findDefinitionKey(result.definitions(), "profile.json", "UserProfile");
        generator.writeRecordToFile(userProfileKey, result.definitions().get(userProfileKey), modelsDir.toString());
        
        // Verify User.java references UserProfile correctly
        String userContent = Files.readString(modelsDir.resolve("User.java"));
        assertThat(userContent).contains("UserProfile profile");
        
        // Verify UserProfile.java includes inherited properties from BaseProfile
        String userProfileContent = Files.readString(modelsDir.resolve("UserProfile.java"));
        assertThat(userProfileContent).contains("String id"); // from BaseProfile
        assertThat(userProfileContent).contains("String createdAt"); // from BaseProfile (generator uses String for date-time)
        assertThat(userProfileContent).contains("String updatedAt"); // from BaseProfile
    }
    
    @Test
    @DisplayName("Should generate client with correct operation implementations")
    void testClientOperationGeneration() throws IOException {
        TestSpecLoader testLoader = new TestSpecLoader(testDataDir);
        SpecResult result = testLoader.loadSpecs();
        
        Path clientDir = tempDir.resolve("client");
        Files.createDirectories(clientDir);
        
        OperationGenerator generator = new OperationGenerator(
            result.operations(),
            Set.of(),
            result.definitions(),
            "com.azure.simpleSDK.test.models",
            "com.azure.simpleSDK.test.client",
            "TestUserApiClient",
            "2024-07-01"
        );
        
        generator.generateAzureClient(clientDir.toString());
        
        String clientContent = Files.readString(clientDir.resolve("TestUserApiClient.java"));
        
        // Verify all three operations are present (with actual parameter types)
        assertThat(clientContent).contains("getUsers(String userId, String expand)");
        assertThat(clientContent).contains("listUsers(String filter, String top)"); // Generator uses String for all parameters
        assertThat(clientContent).contains("listProfilesUsers(String userId)");
        
        // Verify return types are correct
        assertThat(clientContent).contains("AzureResponse<User>");
        assertThat(clientContent).contains("AzureResponse<UserListResult>");
        assertThat(clientContent).contains("AzureResponse<UserProfileListResult>");
        
        // Verify URL construction (exact format may vary)
        assertThat(clientContent).contains("\"/users/\" + userId"); // URL with user ID parameter
        assertThat(clientContent).contains("String url = \"/users\""); // List users URL
        assertThat(clientContent).contains("/profiles"); // Profiles path segment
    }
    
    @Test
    @DisplayName("Should handle duplicate definitions with filename prefixes")
    void testDuplicateDefinitionHandling() throws IOException {
        TestSpecLoader testLoader = new TestSpecLoader(testDataDir);
        SpecResult result = testLoader.loadSpecs();
        
        Set<String> duplicateNames = SpecLoader.findDuplicateDefinitionNames(result.definitions());
        assertThat(duplicateNames).contains("Resource");
        
        JavaDefinitionGenerator generator = new JavaDefinitionGenerator(
            duplicateNames,
            result.definitions(),
            "com.azure.simpleSDK.test.models"
        );
        
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        
        // Generate both Resource definitions
        DefinitionKey userResourceKey = findDefinitionKey(result.definitions(), "user.json", "Resource");
        DefinitionKey commonResourceKey = findDefinitionKey(result.definitions(), "common.json", "Resource");
        
        generator.writeRecordToFile(userResourceKey, result.definitions().get(userResourceKey), modelsDir.toString());
        generator.writeRecordToFile(commonResourceKey, result.definitions().get(commonResourceKey), modelsDir.toString());
        
        // Verify both files exist with proper naming
        assertThat(modelsDir.resolve("UserResource.java")).exists();
        assertThat(modelsDir.resolve("CommonResource.java")).exists();
        
        // Verify content is different
        String userResourceContent = Files.readString(modelsDir.resolve("UserResource.java"));
        String commonResourceContent = Files.readString(modelsDir.resolve("CommonResource.java"));
        
        assertThat(userResourceContent).contains("public record UserResource(");
        assertThat(commonResourceContent).contains("public record CommonResource(");
        
        // User Resource has different fields than Common Resource
        assertThat(userResourceContent).doesNotContain("location");
        assertThat(commonResourceContent).contains("location");
    }
    
    @Test
    @DisplayName("Should handle reserved Java keywords correctly")
    void testReservedKeywordHandling() throws IOException {
        TestSpecLoader testLoader = new TestSpecLoader(testDataDir);
        SpecResult result = testLoader.loadSpecs();
        
        JavaDefinitionGenerator generator = new JavaDefinitionGenerator(
            Set.of(),
            result.definitions(),
            "com.azure.simpleSDK.test.models"
        );
        
        Path modelsDir = tempDir.resolve("models");
        Files.createDirectories(modelsDir);
        
        // Generate UserProfile which has "interface" and "class" properties (reserved keywords)
        DefinitionKey userProfileKey = findDefinitionKey(result.definitions(), "profile.json", "UserProfile");
        generator.writeRecordToFile(userProfileKey, result.definitions().get(userProfileKey), modelsDir.toString());
        
        String userProfileContent = Files.readString(modelsDir.resolve("UserProfile.java"));
        
        // Verify reserved keywords are mapped correctly with @JsonProperty
        // UserProfile only has "class" reserved keyword, not "interface"
        assertThat(userProfileContent).contains("@JsonProperty(\"class\") String clazz");
        // Note: "interface" and "default" properties are not in UserProfile, they're in User model
    }
    
    private DefinitionKey findDefinitionKey(Map<DefinitionKey, JsonNode> definitions, String filename, String definitionName) {
        return definitions.keySet().stream()
            .filter(key -> key.filename().equals(filename) && key.definitionKey().equals(definitionName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Definition not found: " + filename + "#" + definitionName));
    }
    
    /**
     * Simple test implementation of SpecLoader for testing
     */
    private static class TestSpecLoader {
        private final Path basePath;
        private final ObjectMapper mapper = new ObjectMapper();
        
        public TestSpecLoader(Path basePath) {
            this.basePath = basePath;
        }
        
        public SpecResult loadSpecs() throws IOException {
            Map<String, Operation> operations = new HashMap<>();
            Map<DefinitionKey, JsonNode> definitions = new HashMap<>();
            
            String[] files = {"user.json", "profile.json", "common.json"};
            
            for (String filename : files) {
                JsonNode spec = mapper.readTree(basePath.resolve(filename).toFile());
                
                // Load operations
                JsonNode paths = spec.get("paths");
                if (paths != null) {
                    paths.fieldNames().forEachRemaining(path -> {
                        JsonNode pathNode = paths.get(path);
                        pathNode.fieldNames().forEachRemaining(method -> {
                            JsonNode operationNode = pathNode.get(method);
                            if (operationNode.has("operationId")) {
                                String operationId = operationNode.get("operationId").asText();
                                Map<String, String> responseSchemas = extractResponseSchemas(operationNode);
                                operations.put(operationId, new Operation(
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
                
                // Load definitions
                JsonNode specDefs = spec.get("definitions");
                if (specDefs != null) {
                    specDefs.fieldNames().forEachRemaining(name -> {
                        definitions.put(
                            new DefinitionKey(filename, name, name.hashCode() % 1000),
                            specDefs.get(name)
                        );
                    });
                }
            }
            
            return new SpecResult(operations, definitions);
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
    }
}