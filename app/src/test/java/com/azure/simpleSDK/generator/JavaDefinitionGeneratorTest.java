package com.azure.simpleSDK.generator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JavaDefinitionGeneratorTest {
    
    private JavaDefinitionGenerator generator;
    private JavaDefinitionGenerator generatorWithDuplicates;
    
    @BeforeEach
    void setUp() {
        generator = new JavaDefinitionGenerator(new HashSet<>());
        
        Set<String> duplicateNames = new HashSet<>();
        duplicateNames.add("Resource");
        duplicateNames.add("SubResource");
        generatorWithDuplicates = new JavaDefinitionGenerator(duplicateNames);
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
    @DisplayName("Should handle external reference with .json extension removal")
    void testResolveReference_JsonExtensionRemoval() {
        String ref = "./publicIpAddress.json#/definitions/PublicIPAddress";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("PublicIPAddress", result);
    }
    
    @Test
    @DisplayName("Should return Object for invalid reference patterns")
    void testResolveReference_InvalidPattern() {
        String ref = "invalid-reference-pattern";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("Object", result);
    }
    
    @Test
    @DisplayName("Should return Object for empty reference")
    void testResolveReference_EmptyReference() {
        String ref = "";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("Object", result);
    }
    
    @Test
    @DisplayName("Should return Object for null reference")
    void testResolveReference_NullReference() {
        String ref = null;
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("Object", result);
    }
    
    @Test
    @DisplayName("Should handle reference without definitions path")
    void testResolveReference_NoDefinitionsPath() {
        String ref = "#/properties/someProperty";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("Object", result);
    }
    
    @Test
    @DisplayName("Should handle external reference with nested path")
    void testResolveReference_ExternalWithNestedPath() {
        String ref = "./nested/folder/file.json#/definitions/NestedType";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generator.resolveReference(ref, currentFilename);
        
        assertEquals("NestedType", result);
    }
    
    @Test
    @DisplayName("Should handle duplicate definition with nested file path")
    void testResolveReference_DuplicateWithNestedPath() {
        String ref = "./nested/folder/file.json#/definitions/Resource";
        String currentFilename = "virtualNetworkGateway.json";
        
        String result = generatorWithDuplicates.resolveReference(ref, currentFilename);
        
        assertEquals("FileResource", result);
    }
}