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
        definitions.put(new DefinitionKey("virtualNetworkGateway.json", "VirtualNetworkGateway"), emptyObject);
        definitions.put(new DefinitionKey("virtualNetworkGateway.json", "Resource"), emptyObject);
        definitions.put(new DefinitionKey("virtualNetworkGateway.json", "virtualNetworkGatewayConnectionStatus"), emptyObject);
        definitions.put(new DefinitionKey("networkInterface.json", "NetworkInterface"), emptyObject);
        definitions.put(new DefinitionKey("networkInterface.json", "Resource"), emptyObject);
        definitions.put(new DefinitionKey("publicIpAddress.json", "PublicIPAddress"), emptyObject);
        definitions.put(new DefinitionKey("applicationGateway.json", "ApplicationGatewayFirewallRuleGroup"), emptyObject);
        
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
        
        assertTrue(exception.getMessage().contains("Invalid external reference format"));
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
    
}