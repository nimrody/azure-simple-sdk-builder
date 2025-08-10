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
        // Check if this is actually an enum definition
        if (isEnumDefinition(definition)) {
            return generateEnum(definitionKey, definition);
        }
        
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
                String fieldDeclaration = generateFieldDeclaration(javaType, fieldName, propertyName);
                fields.add(fieldDeclaration);
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
    
    private boolean isEnumDefinition(JsonNode definition) {
        // Check if this definition has type: "string" and an enum array
        JsonNode typeNode = definition.get("type");
        JsonNode enumNode = definition.get("enum");
        return typeNode != null && "string".equals(typeNode.asText()) && enumNode != null && enumNode.isArray();
    }
    
    private String generateEnum(DefinitionKey definitionKey, JsonNode definition) {
        String className = generateClassName(definitionKey);
        JsonNode enumValues = definition.get("enum");
        
        StringBuilder enumBuilder = new StringBuilder();
        enumBuilder.append("package com.azure.simpleSDK;\n\n");
        enumBuilder.append("import com.fasterxml.jackson.annotation.JsonValue;\n\n");
        enumBuilder.append("public enum ").append(className).append(" {\n");
        
        List<String> enumConstants = new ArrayList<>();
        for (int i = 0; i < enumValues.size(); i++) {
            String enumValue = enumValues.get(i).asText();
            String enumConstantName = convertToEnumConstantName(enumValue);
            
            if (enumConstantName.equals(enumValue)) {
                // No need for @JsonValue if the constant name matches the value
                enumConstants.add("    " + enumConstantName);
            } else {
                // Use @JsonValue to map the constant to the original value
                enumConstants.add("    " + enumConstantName + "(\"" + enumValue + "\")");
            }
        }
        
        enumBuilder.append(String.join(",\n", enumConstants));
        
        // Add constructor and @JsonValue method if needed
        boolean needsJsonValue = enumConstants.stream().anyMatch(constant -> constant.contains("(\""));
        if (needsJsonValue) {
            enumBuilder.append(";\n\n");
            enumBuilder.append("    private final String value;\n\n");
            enumBuilder.append("    ").append(className).append("(String value) {\n");
            enumBuilder.append("        this.value = value;\n");
            enumBuilder.append("    }\n\n");
            enumBuilder.append("    @JsonValue\n");
            enumBuilder.append("    public String getValue() {\n");
            enumBuilder.append("        return value;\n");
            enumBuilder.append("    }\n");
        } else {
            enumBuilder.append("\n");
        }
        
        enumBuilder.append("}\n");
        
        return enumBuilder.toString();
    }
    
    private String convertToEnumConstantName(String enumValue) {
        // Convert enum value to valid Java enum constant name
        // Replace non-alphanumeric characters with underscores and convert to uppercase
        String constantName = enumValue
            .replaceAll("[^a-zA-Z0-9]", "_")
            .toUpperCase();
            
        // Ensure it doesn't start with a digit
        if (constantName.matches("^\\d.*")) {
            constantName = "_" + constantName;
        }
        
        // Handle empty string or just underscores
        if (constantName.isEmpty() || constantName.matches("^_+$")) {
            constantName = "UNKNOWN";
        }
        
        return constantName;
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
        
        // Check for inline enum (has both type: "string" and enum array)
        if (property.has("type") && "string".equals(property.get("type").asText()) && property.has("enum")) {
            // For inline enums, we could either generate them on-the-fly or use String
            // For now, let's use String but this could be enhanced to generate inline enums
            return "String";
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
        String fieldName;
        if (propertyName.contains("-")) {
            String[] parts = propertyName.split("-");
            StringBuilder camelCase = new StringBuilder(parts[0].toLowerCase());
            for (int i = 1; i < parts.length; i++) {
                camelCase.append(capitalizeFirstLetter(parts[i].toLowerCase()));
            }
            fieldName = camelCase.toString();
        } else {
            fieldName = propertyName;
        }
        
        // Handle Java reserved words
        return mapReservedWord(fieldName);
    }
    
    private String mapReservedWord(String fieldName) {
        switch (fieldName) {
            case "default":
                return "dflt";
            case "interface":
                return "iface";
            case "class":
                return "clazz";
            case "public":
                return "publicField";
            case "private":
                return "privateField";
            case "protected":
                return "protectedField";
            case "static":
                return "staticField";
            case "final":
                return "finalField";
            case "abstract":
                return "abstractField";
            case "synchronized":
                return "synchronizedField";
            case "volatile":
                return "volatileField";
            case "transient":
                return "transientField";
            case "native":
                return "nativeField";
            case "strictfp":
                return "strictfpField";
            case "return":
                return "returnValue";
            case "void":
                return "voidField";
            case "if":
                return "ifField";
            case "else":
                return "elseField";
            case "while":
                return "whileField";
            case "for":
                return "forField";
            case "do":
                return "doField";
            case "switch":
                return "switchField";
            case "case":
                return "caseField";
            case "break":
                return "breakField";
            case "continue":
                return "continueField";
            case "try":
                return "tryField";
            case "catch":
                return "catchField";
            case "finally":
                return "finallyField";
            case "throw":
                return "throwField";
            case "throws":
                return "throwsField";
            case "new":
                return "newField";
            case "this":
                return "thisField";
            case "super":
                return "superField";
            case "null":
                return "nullField";
            case "true":
                return "trueField";
            case "false":
                return "falseField";
            case "instanceof":
                return "instanceofField";
            case "package":
                return "packageField";
            case "import":
                return "importField";
            case "extends":
                return "extendsField";
            case "implements":
                return "implementsField";
            default:
                return fieldName;
        }
    }
    
    private boolean isReservedWord(String fieldName) {
        return !fieldName.equals(mapReservedWord(fieldName));
    }
    
    private String generateFieldDeclaration(String javaType, String fieldName, String originalPropertyName) {
        if (isReservedWord(originalPropertyName) || !fieldName.equals(originalPropertyName)) {
            return String.format("    @JsonProperty(\"%s\") %s %s", originalPropertyName, javaType, fieldName);
        } else {
            return String.format("    %s %s", javaType, fieldName);
        }
    }
    
    private String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}
