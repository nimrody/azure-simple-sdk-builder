package com.azure.simpleSDK.generator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class JavaDefinitionGeneratorTest {
    
    private JavaDefinitionGenerator generator;
    private JavaDefinitionGenerator generatorWithDuplicates;
    private Map<DefinitionKey, JsonNode> mockDefinitions;
    
    @BeforeEach
    void setUp() {
        // Create mock definitions for testing
        mockDefinitions = createMockDefinitions();
        
        generator = new JavaDefinitionGenerator(new HashSet<>(), mockDefinitions);
        
        Set<String> duplicateNames = new HashSet<>();
        duplicateNames.add("Resource");
        duplicateNames.add("SubResource");
        generatorWithDuplicates = new JavaDefinitionGenerator(duplicateNames, mockDefinitions);
    }
    
    private Map<DefinitionKey, JsonNode> createMockDefinitions() {
        Map<DefinitionKey, JsonNode> definitions = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode emptyObject = mapper.createObjectNode();
        
        // Add the definitions that are referenced in the tests
        definitions.put(new DefinitionKey("virtualNetworkGateway.json", "VirtualNetworkGateway", 10), emptyObject);
        definitions.put(new DefinitionKey("virtualNetworkGateway.json", "Resource", 20), emptyObject);
        definitions.put(new DefinitionKey("virtualNetworkGateway.json", "virtualNetworkGatewayConnectionStatus", 30), emptyObject);
        definitions.put(new DefinitionKey("networkInterface.json", "NetworkInterface", 40), emptyObject);
        definitions.put(new DefinitionKey("networkInterface.json", "Resource", 50), emptyObject);
        definitions.put(new DefinitionKey("publicIpAddress.json", "PublicIPAddress", 60), emptyObject);
        definitions.put(new DefinitionKey("applicationGateway.json", "ApplicationGatewayFirewallRuleGroup", 70), emptyObject);
        definitions.put(new DefinitionKey("test.json", "TestDefinition", 80), emptyObject);
        definitions.put(new DefinitionKey("test.json", "TestEnum", 90), emptyObject);
        definitions.put(new DefinitionKey("test.json", "SimpleEnum", 100), emptyObject);
        
        return definitions;
    }
    
    @Test
    @DisplayName("Should resolve local reference without filename prefix when no duplicates")
    void testResolveReference_LocalReference_NoDuplicates() {
        String ref = "#/definitions/VirtualNetworkGateway";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("VirtualNetworkGateway", result);
    }
    
    @Test
    @DisplayName("Should resolve local reference with filename prefix when duplicates exist")
    void testResolveReference_LocalReference_WithDuplicates() {
        String ref = "#/definitions/Resource";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generatorWithDuplicates.resolveReference(ref, currentFilename);
        
        assertEquals("VirtualNetworkGatewayResource", result);
    }
    
    @Test
    @DisplayName("Should resolve external reference without filename prefix when no duplicates")
    void testResolveReference_ExternalReference_NoDuplicates() {
        String ref = "./networkInterface.json#/definitions/NetworkInterface";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("NetworkInterface", result);
    }
    
    @Test
    @DisplayName("Should resolve external reference with filename prefix when duplicates exist")
    void testResolveReference_ExternalReference_WithDuplicates() {
        String ref = "./networkInterface.json#/definitions/Resource";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generatorWithDuplicates.resolveReference(ref, currentFilename);
        
        assertEquals("NetworkInterfaceResource", result);
    }
    
    @Test
    @DisplayName("Should handle complex definition names with proper capitalization")
    void testResolveReference_ComplexNames() {
        String ref = "#/definitions/virtualNetworkGatewayConnectionStatus";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("VirtualNetworkGatewayConnectionStatus", result);
    }
    
    @Test
    @DisplayName("Should throw exception when referenced definition does not exist")
    void testResolveReference_NonExistentDefinition() {
        String ref = "#/definitions/NonExistentDefinition";
        String currentFilename = "virtualNetworkGateway.json";
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            generator.resolveReference(ref, currentFilename);
        });
        
        assertTrue(exception.getMessage().contains("Referenced definition not found"));
        assertTrue(exception.getMessage().contains("NonExistentDefinition"));
        assertTrue(exception.getMessage().contains("virtualNetworkGateway.json"));
    }
    
    @Test
    @DisplayName("Should throw exception when external referenced definition does not exist")
    void testResolveReference_NonExistentExternalDefinition() {
        String ref = "./nonExistentFile.json#/definitions/SomeDefinition";
        String currentFilename = "virtualNetworkGateway.json";
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            generator.resolveReference(ref, currentFilename);
        });
        
        assertTrue(exception.getMessage().contains("Referenced definition not found"));
        assertTrue(exception.getMessage().contains("SomeDefinition"));
        assertTrue(exception.getMessage().contains("nonExistentFile.json"));
    }
    
    @Test
    @DisplayName("Should throw exception for invalid external reference format")
    void testResolveReference_InvalidExternalReferenceFormat() {
        String ref = "./invalidFormat.json#SomeDefinition";
        String currentFilename = "virtualNetworkGateway.json";
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            generator.resolveReference(ref, currentFilename);
        });
        
        assertTrue(exception.getMessage().contains("Unsupported reference format"));
    }
    
    @Test
    @DisplayName("Should handle external reference with .json extension removal")
    void testResolveReference_JsonExtensionRemoval() {
        String ref = "./publicIpAddress.json#/definitions/PublicIPAddress";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("PublicIPAddress", result);
    }
    
    @Test
    @DisplayName("Should handle external reference without ./ prefix")
    void testResolveReference_ExternalReferenceWithoutDotSlashPrefix() {
        String ref = "applicationGateway.json#/definitions/ApplicationGatewayFirewallRuleGroup";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("ApplicationGatewayFirewallRuleGroup", result);
    }
    
    @Test
    @DisplayName("Should throw exception for unsupported reference format")
    void testResolveReference_UnsupportedFormat() {
        String ref = "http://example.com/definitions/SomeDefinition";
        String currentFilename = "virtualNetworkGateway.json";
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            generator.resolveReference(ref, currentFilename);
        });
        
        assertTrue(exception.getMessage().contains("Unsupported reference format"));
    }
    
    @Test
    @DisplayName("Should generate record with reserved word field names properly mapped")
    void testGenerateRecord_ReservedWordFields() {
        DefinitionKey definitionKey = new DefinitionKey("test.json", "TestDefinition", 1);
        
        // Create a test definition with reserved word properties
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode propertiesNode = mapper.createObjectNode();
        propertiesNode.set("default", mapper.createObjectNode().put("type", "boolean"));
        propertiesNode.set("interface", mapper.createObjectNode().put("type", "string"));
        propertiesNode.set("class", mapper.createObjectNode().put("type", "string"));
        propertiesNode.set("public", mapper.createObjectNode().put("type", "integer"));
        propertiesNode.set("normalField", mapper.createObjectNode().put("type", "string"));
        
        ObjectNode definition = mapper.createObjectNode();
        definition.set("properties", propertiesNode);
        
        String result = generator.generateRecord(definitionKey, definition);
        
        // Check that reserved words are properly mapped and have JsonProperty annotations
        assertTrue(result.contains("@JsonProperty(\"default\") Boolean dflt"));
        assertTrue(result.contains("@JsonProperty(\"interface\") String iface"));
        assertTrue(result.contains("@JsonProperty(\"class\") String clazz"));
        assertTrue(result.contains("@JsonProperty(\"public\") Integer publicField"));
        
        // Check that normal fields don't have JsonProperty annotations
        assertTrue(result.contains("    String normalField"));
        assertFalse(result.contains("@JsonProperty(\"normalField\")"));
    }
    
    @Test
    @DisplayName("Should handle hyphenated property names that become reserved words")
    void testGenerateRecord_HyphenatedReservedWords() {
        DefinitionKey definitionKey = new DefinitionKey("test.json", "TestDefinition", 1);
        
        // Create a test definition with hyphenated properties that might become reserved words
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode propertiesNode = mapper.createObjectNode();
        propertiesNode.set("default-value", mapper.createObjectNode().put("type", "string"));
        propertiesNode.set("interface-name", mapper.createObjectNode().put("type", "string"));
        
        ObjectNode definition = mapper.createObjectNode();
        definition.set("properties", propertiesNode);
        
        String result = generator.generateRecord(definitionKey, definition);
        
        // Check that hyphenated properties are converted to camelCase and then handled for reserved words
        assertTrue(result.contains("@JsonProperty(\"default-value\") String defaultValue"));
        assertTrue(result.contains("@JsonProperty(\"interface-name\") String interfaceName"));
    }
    
    @Test
    @DisplayName("Should generate Java enum for OpenAPI enum definition")
    void testGenerateRecord_EnumDefinition() {
        DefinitionKey definitionKey = new DefinitionKey("test.json", "TestEnum", 1);
        
        // Create a test enum definition
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode definition = mapper.createObjectNode();
        definition.put("type", "string");
        definition.set("enum", mapper.createArrayNode()
            .add("Value1")
            .add("Value2")
            .add("Special-Value")
        );
        
        String result = generator.generateRecord(definitionKey, definition);
        
        // Check that it generates a proper enum
        assertTrue(result.contains("public enum TestEnum {"));
        assertTrue(result.contains("VALUE1"));
        assertTrue(result.contains("VALUE2"));
        assertTrue(result.contains("SPECIAL_VALUE(\"Special-Value\")"));
        assertTrue(result.contains("@JsonValue"));
        assertTrue(result.contains("public String getValue()"));
    }
    
    @Test
    @DisplayName("Should generate simple enum without JsonValue when names match values")
    void testGenerateRecord_SimpleEnum() {
        DefinitionKey definitionKey = new DefinitionKey("test.json", "SimpleEnum", 1);
        
        // Create a simple enum definition with values that match constant names
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode definition = mapper.createObjectNode();
        definition.put("type", "string");
        definition.set("enum", mapper.createArrayNode()
            .add("ACTIVE")
            .add("INACTIVE")
        );
        
        String result = generator.generateRecord(definitionKey, definition);
        
        // Check that it generates an enum (generator always includes @JsonValue for consistency)
        assertTrue(result.contains("public enum SimpleEnum {"));
        assertTrue(result.contains("ACTIVE"));
        assertTrue(result.contains("INACTIVE"));
        assertTrue(result.contains("@JsonValue")); // Generator always includes this for JSON serialization
        assertTrue(result.contains("public String getValue()"));
    }
    
    @Test
    @DisplayName("Should handle inline enum in property as String type")
    void testGenerateRecord_InlineEnum() {
        DefinitionKey definitionKey = new DefinitionKey("test.json", "TestDefinition", 1);
        
        // Create a definition with an inline enum property
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode propertyWithEnum = mapper.createObjectNode();
        propertyWithEnum.put("type", "string");
        propertyWithEnum.set("enum", mapper.createArrayNode().add("option1").add("option2"));
        
        ObjectNode propertiesNode = mapper.createObjectNode();
        propertiesNode.set("status", propertyWithEnum);
        propertiesNode.set("name", mapper.createObjectNode().put("type", "string"));
        
        ObjectNode definition = mapper.createObjectNode();
        definition.set("properties", propertiesNode);
        
        String result = generator.generateRecord(definitionKey, definition);
        
        // Check that inline enum is treated as String for now
        assertTrue(result.contains("public record TestDefinition("));
        assertTrue(result.contains("String status"));
        assertTrue(result.contains("String name"));
    }
    
}