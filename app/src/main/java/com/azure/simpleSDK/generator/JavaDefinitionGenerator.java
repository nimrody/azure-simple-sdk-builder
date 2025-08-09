package com.azure.simpleSDK.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

public class JavaDefinitionGenerator {
    private final Set<String> duplicateDefinitionNames;
    private final Map<DefinitionKey, JsonNode> definitions;
    
    public JavaDefinitionGenerator(Set<String> duplicateDefinitionNames, Map<DefinitionKey, JsonNode> definitions) {
        this.duplicateDefinitionNames = duplicateDefinitionNames != null ? duplicateDefinitionNames : new HashSet<>();
        this.definitions = definitions != null ? definitions : Map.of();
    }
    
    public String generateRecord(DefinitionKey definitionKey, JsonNode definition) {
        String className = generateClassName(definitionKey);
        StringBuilder recordBuilder = new StringBuilder();
        
        recordBuilder.append("package com.azure.simpleSDK;\n\n");
        recordBuilder.append("import com.fasterxml.jackson.annotation.JsonProperty;\n");
        recordBuilder.append("import java.util.*;\n\n");
        recordBuilder.append("public record ").append(className).append("(\n");
        
        List<String> fields = new ArrayList<>();
        JsonNode properties = definition.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fieldNames().forEachRemaining(propertyName -> {
                if (shouldIgnoreProperty(propertyName)) {
                    return;
                }
                
                JsonNode property = properties.get(propertyName);
                String javaType = getJavaType(property, definitionKey.filename());
                String fieldName = convertToJavaFieldName(propertyName);
                fields.add("    " + javaType + " " + fieldName);
            });
        }
        
        recordBuilder.append(String.join(",\n", fields));
        recordBuilder.append("\n) {\n}");
        
        return recordBuilder.toString();
    }
    
    public String getClassName(DefinitionKey definitionKey) {
        return generateClassName(definitionKey);
    }
    
    public void writeRecordToFile(DefinitionKey definitionKey, JsonNode definition, String outputDir) throws IOException {
        String className = generateClassName(definitionKey);
        String recordContent = generateRecord(definitionKey, definition);
        
        Path outputPath = Paths.get(outputDir);
        if (!Files.exists(outputPath)) {
            Files.createDirectories(outputPath);
        }
        
        Path filePath = outputPath.resolve(className + ".java");
        Files.writeString(filePath, recordContent);
    }
    
    private String generateClassName(DefinitionKey definitionKey) {
        String definitionName = definitionKey.definitionKey();
        
        if (duplicateDefinitionNames.contains(definitionName)) {
            String filename = definitionKey.filename();
            String baseName = filename.replaceAll("\\.json$", "");
            return capitalizeFirstLetter(baseName) + capitalizeFirstLetter(definitionName);
        }
        
        return capitalizeFirstLetter(definitionName);
    }
    
    private String getJavaType(JsonNode property, String currentFilename) {
        if (property.has("$ref")) {
            return resolveReference(property.get("$ref").asText(), currentFilename);
        }
        
        if (property.has("type")) {
            String type = property.get("type").asText();
            switch (type) {
                case "string":
                    return "String";
                case "integer":
                    return property.has("format") && "int64".equals(property.get("format").asText()) ? "Long" : "Integer";
                case "number":
                    return "Double";
                case "boolean":
                    return "Boolean";
                case "array":
                    JsonNode items = property.get("items");
                    if (items != null) {
                        String itemType = getJavaType(items, currentFilename);
                        return "List<" + itemType + ">";
                    }
                    return "List<Object>";
                case "object":
                    if (property.has("additionalProperties")) {
                        JsonNode additionalProps = property.get("additionalProperties");
                        if (additionalProps.isBoolean() && additionalProps.asBoolean()) {
                            return "java.util.Map<String, Object>";
                        } else if (additionalProps.isObject()) {
                            String valueType = getJavaType(additionalProps, currentFilename);
                            return "Map<String, " + valueType + ">";
                        }
                    }
                    return "Map<String, Object>";
                default:
                    return "Object";
            }
        }
        
        return "Object";
    }
    
    String resolveReference(String ref, String currentFilename) {
        String filename;
        String definitionName;

        if (ref.startsWith("#/definitions/")) {
            // handle references to local definitions "#/definitions/VirtualNetworkGatewayPropertiesFormat"
            filename = currentFilename.replace(".json", "");
            definitionName = ref.replace("#/definitions/", "");
            
        } else if (ref.startsWith("./")) {
            // handle external references like "./networkInterface.json#/definitions/NetworkInterface"
            String[] parts = ref.split("#/definitions/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid external reference format: " + ref);
            }
            filename = parts[0].replace("./", "").replace(".json", "");
            definitionName = parts[1];
        } else if (ref.contains(".json#/definitions/")) {
            // handle external references without "./" prefix like "applicationGateway.json#/definitions/ApplicationGatewayFirewallRuleGroup"
            String[] parts = ref.split("#/definitions/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid external reference format: " + ref);
            }
            filename = parts[0].replace(".json", "");
            definitionName = parts[1];
        } else {
            throw new IllegalArgumentException("Unsupported reference format: " + ref);
        }

        // Validate that the referenced definition exists in the scanned definitions
        String fullFilename = filename + ".json";
        DefinitionKey referencedKey = new DefinitionKey(fullFilename, definitionName);
        if (!definitions.containsKey(referencedKey)) {
            throw new IllegalArgumentException(
                String.format("Referenced definition not found: %s in file %s. Available definitions: %s",
                    definitionName, fullFilename, getAvailableDefinitionsForFile(fullFilename)));
        }

        if (duplicateDefinitionNames.contains(definitionName)) {
            return capitalizeFirstLetter(filename) + capitalizeFirstLetter(definitionName);
        } else {
            return capitalizeFirstLetter(definitionName);
        }
    }
    
    private String getAvailableDefinitionsForFile(String filename) {
        return definitions.keySet().stream()
            .filter(key -> key.filename().equals(filename))
            .map(DefinitionKey::definitionKey)
            .sorted()
            .collect(java.util.stream.Collectors.joining(", "));
    }
    
    private boolean shouldIgnoreProperty(String propertyName) {
        return "etag".equals(propertyName) || propertyName.startsWith("x-ms-");
    }
    
    private String convertToJavaFieldName(String propertyName) {
        if (propertyName.contains("-")) {
            String[] parts = propertyName.split("-");
            StringBuilder camelCase = new StringBuilder(parts[0].toLowerCase());
            for (int i = 1; i < parts.length; i++) {
                camelCase.append(capitalizeFirstLetter(parts[i].toLowerCase()));
            }
            return camelCase.toString();
        }
        return propertyName;
    }
    
    private String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
