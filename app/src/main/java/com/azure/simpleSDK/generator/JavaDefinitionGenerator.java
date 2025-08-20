package com.azure.simpleSDK.generator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JavaDefinitionGenerator {
    private final Set<String> duplicateDefinitionNames;
    private final Map<DefinitionKey, JsonNode> definitions;
    private final Map<String, JsonNode> inlineEnums = new HashMap<>();
    private final String packageName;
    
    public JavaDefinitionGenerator(Set<String> duplicateDefinitionNames, Map<DefinitionKey, JsonNode> definitions) {
        this.duplicateDefinitionNames = duplicateDefinitionNames != null ? duplicateDefinitionNames : new HashSet<>();
        this.definitions = definitions != null ? definitions : Map.of();
        this.packageName = "com.azure.simpleSDK.models"; // Default package for backwards compatibility
    }
    
    public JavaDefinitionGenerator(Set<String> duplicateDefinitionNames, Map<DefinitionKey, JsonNode> definitions, String packageName) {
        this.duplicateDefinitionNames = duplicateDefinitionNames != null ? duplicateDefinitionNames : new HashSet<>();
        this.definitions = definitions != null ? definitions : Map.of();
        this.packageName = packageName != null ? packageName : "com.azure.simpleSDK.models";
    }
    
    public String generateRecord(DefinitionKey definitionKey, JsonNode definition) {
        // Check if this is actually an enum definition
        if (isEnumDefinition(definition)) {
            return generateEnum(definitionKey, definition);
        }
        
        // Extract any inline enums from properties before generating the record
        extractInlineEnums(definition, definitionKey.filename());
        
        String className = generateClassName(definitionKey);
        StringBuilder recordBuilder = new StringBuilder();
        
        appendPackageAndImports(recordBuilder, "JsonProperty", "java.util.*");
        appendSourceComment(recordBuilder, definitionKey);
        recordBuilder.append("public record ").append(className).append("(\n");
        
        List<String> fields = new ArrayList<>();
        Set<String> addedFieldNames = new HashSet<>();
        
        // First, collect fields from allOf references (inherited fields)
        JsonNode allOf = definition.get("allOf");
        if (allOf != null && allOf.isArray()) {
            Set<String> visitedRefs = new HashSet<>();
            for (JsonNode allOfItem : allOf) {
                JsonNode ref = allOfItem.get("$ref");
                if (ref != null && ref.isTextual()) {
                    String refValue = ref.asText();
                    
                    // Prevent infinite recursion
                    if (visitedRefs.contains(refValue)) {
                        continue;
                    }
                    visitedRefs.add(refValue);
                    
                    ResolvedDefinition resolvedDef = resolveAllOfReference(refValue, definitionKey.filename());
                    if (resolvedDef != null) {
                        addFieldsFromDefinition(resolvedDef.getDefinition(), fields, resolvedDef.getFilename(), addedFieldNames);
                    }
                }
            }
        }
        
        // Then, collect fields from direct properties (skip duplicates)
        JsonNode properties = definition.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fieldNames().forEachRemaining(propertyName -> {
                if (shouldIgnoreProperty(propertyName)) {
                    return;
                }
                
                String fieldName = convertToJavaFieldName(propertyName);
                if (addedFieldNames.contains(fieldName)) {
                    return; // Skip duplicate field
                }
                
                JsonNode property = properties.get(propertyName);
                String javaType = getJavaType(property, definitionKey.filename());
                String fieldDeclaration = generateFieldDeclaration(javaType, fieldName, propertyName);
                fields.add(fieldDeclaration);
                addedFieldNames.add(fieldName);
            });
        }
        
        recordBuilder.append(String.join(",\n", fields));
        recordBuilder.append("\n) {\n}");
        
        return recordBuilder.toString();
    }
    
    public String getClassName(DefinitionKey definitionKey) {
        return generateClassName(definitionKey);
    }
    
    public Map<String, JsonNode> getInlineEnums() {
        return new HashMap<>(inlineEnums);
    }
    
    public void writeInlineEnumToFile(String enumName, JsonNode enumDefinition, String outputDir) throws IOException {
        String enumContent = generateInlineEnum(enumName, enumDefinition);
        String fileName = capitalizeFirstLetter(enumName) + ".java";
        writeToFile(enumContent, fileName, outputDir);
    }
    
    private String generateInlineEnum(String enumName, JsonNode enumDefinition) {
        JsonNode enumValues = enumDefinition.get("enum");
        
        StringBuilder enumBuilder = new StringBuilder();
        appendPackageAndImports(enumBuilder, "JsonValue", "JsonCreator");
        enumBuilder.append("public enum ").append(capitalizeFirstLetter(enumName)).append(" {\n");
        
        appendEnumBody(enumBuilder, enumValues, capitalizeFirstLetter(enumName));
        
        return enumBuilder.toString();
    }
    
    public void writeRecordToFile(DefinitionKey definitionKey, JsonNode definition, String outputDir) throws IOException {
        String className = generateClassName(definitionKey);
        String recordContent = generateRecord(definitionKey, definition);
        String fileName = className + ".java";
        writeToFile(recordContent, fileName, outputDir);
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
        appendPackageAndImports(enumBuilder, "JsonValue", "JsonCreator");
        appendSourceComment(enumBuilder, definitionKey);
        enumBuilder.append("public enum ").append(className).append(" {\n");
        
        appendEnumBody(enumBuilder, enumValues, className);
        
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
    
    private void extractInlineEnums(JsonNode definition, String filename) {
        JsonNode properties = definition.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fieldNames().forEachRemaining(propertyName -> {
                JsonNode property = properties.get(propertyName);
                extractInlineEnumsFromProperty(property);
            });
        }
    }
    
    private void extractInlineEnumsFromProperty(JsonNode property) {
        if (isInlineEnum(property)) {
            JsonNode xMsEnum = property.get("x-ms-enum");
            if (xMsEnum != null && xMsEnum.has("name")) {
                String rawEnumName = xMsEnum.get("name").asText();
                String enumName = cleanEnumName(rawEnumName);
                // Create a synthetic enum definition from the inline enum
                JsonNode enumDefinition = createEnumDefinition(property, xMsEnum);
                inlineEnums.put(enumName, enumDefinition);
            }
        } else if (property.has("type")) {
            String type = property.get("type").asText();
            if ("array".equals(type)) {
                JsonNode items = property.get("items");
                if (items != null) {
                    extractInlineEnumsFromProperty(items);
                }
            } else if ("object".equals(type)) {
                JsonNode additionalProperties = property.get("additionalProperties");
                if (additionalProperties != null && additionalProperties.isObject()) {
                    extractInlineEnumsFromProperty(additionalProperties);
                }
                JsonNode properties = property.get("properties");
                if (properties != null && properties.isObject()) {
                    properties.fieldNames().forEachRemaining(propName -> {
                        JsonNode nestedProperty = properties.get(propName);
                        extractInlineEnumsFromProperty(nestedProperty);
                    });
                }
            }
        }
    }
    
    private boolean isInlineEnum(JsonNode property) {
        return property.has("type") && "string".equals(property.get("type").asText()) 
               && property.has("enum") && property.has("x-ms-enum");
    }
    
    private String cleanEnumName(String enumName) {
        // Remove leading and trailing spaces and replace internal spaces with underscores
        return enumName.trim().replaceAll("\\s+", "_");
    }
    
    private JsonNode createEnumDefinition(JsonNode property, JsonNode xMsEnum) {
        ObjectMapper mapper = new ObjectMapper();
        var enumDef = mapper.createObjectNode();
        enumDef.put("type", "string");
        enumDef.set("enum", property.get("enum"));
        if (property.has("description")) {
            enumDef.set("description", property.get("description"));
        }
        enumDef.set("x-ms-enum", xMsEnum);
        return enumDef;
    }
    
    private String generateClassName(DefinitionKey definitionKey) {
        String definitionName = definitionKey.definitionKey();
        
        // Sanitize definition name to be a valid Java class name
        // Replace dots and other invalid characters with empty string or underscore
        String sanitizedName = sanitizeJavaClassName(definitionName);
        
        if (duplicateDefinitionNames.contains(definitionName)) {
            String filename = definitionKey.filename();
            String baseName = filename.replaceAll("\\.json$", "");
            return capitalizeFirstLetter(baseName) + capitalizeFirstLetter(sanitizedName);
        }
        
        return capitalizeFirstLetter(sanitizedName);
    }
    
    private String sanitizeJavaClassName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        
        // Replace dots and other invalid characters with empty string to create valid Java class name
        // For "TypeSpec.Http.OkResponse" -> "TypeSpecHttpOkResponse"
        return name.replaceAll("\\.", "").replaceAll("[^a-zA-Z0-9_]", "");
    }
    
    private String getJavaType(JsonNode property, String currentFilename) {
        if (property.has("$ref")) {
            String refValue = property.get("$ref").asText();
            return resolveReference(refValue, currentFilename);
        }
        
        // Check for inline enum (has both type: "string" and enum array)
        if (property.has("type") && "string".equals(property.get("type").asText()) && property.has("enum")) {
            // Check if this inline enum has x-ms-enum with name
            JsonNode xMsEnum = property.get("x-ms-enum");
            if (xMsEnum != null && xMsEnum.has("name")) {
                String rawEnumName = xMsEnum.get("name").asText();
                String enumName = cleanEnumName(rawEnumName);
                return capitalizeFirstLetter(enumName);
            }
            // Fallback to String if no x-ms-enum name is provided
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
            // handle external references like "applicationGateway.json#/definitions/ApplicationGatewayFirewallRuleGroup"
            // or "../../../common-types/v1/common.json#/definitions/SubResource"
            String[] parts = ref.split("#/definitions/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid external reference format: " + ref);
            }
            // Extract just the filename from the path (e.g., "common" from "../../../common-types/v1/common.json")
            String filePath = parts[0];
            filename = Paths.get(filePath).getFileName().toString().replace(".json", "");
            definitionName = parts[1];
        } else {
            throw new IllegalArgumentException("Unsupported reference format: " + ref);
        }

        // Validate that the referenced definition exists in the scanned definitions
        String fullFilename = filename + ".json";
        DefinitionKey referencedKey = findDefinitionKey(fullFilename, definitionName);
        if (referencedKey == null) {
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
    
    private DefinitionKey findDefinitionKey(String filename, String definitionName) {
        return definitions.keySet().stream()
            .filter(key -> key.filename().equals(filename) && key.definitionKey().equals(definitionName))
            .findFirst()
            .orElse(null);
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
    
    private void appendPackageAndImports(StringBuilder builder, String... jacksonImports) {
        builder.append("package ").append(packageName).append(";\n\n");
        
        if (jacksonImports.length > 0) {
            for (String jacksonImport : jacksonImports) {
                if (!jacksonImport.startsWith("java.")) {
                    builder.append("import com.fasterxml.jackson.annotation.").append(jacksonImport).append(";\n");
                } else {
                    builder.append("import ").append(jacksonImport).append(";\n");
                }
            }
            builder.append("\n");
        }
    }
    
    private void appendSourceComment(StringBuilder builder, DefinitionKey definitionKey) {
        builder.append("// Generated from ").append(definitionKey.filename()).append(":").append(definitionKey.lineNumber()).append("\n");
    }
    
    private void appendEnumBody(StringBuilder enumBuilder, JsonNode enumValues, String className) {
        List<String> enumConstants = new ArrayList<>();
        
        // Generate constants consistently
        for (int i = 0; i < enumValues.size(); i++) {
            String enumValue = enumValues.get(i).asText();
            String enumConstantName = convertToEnumConstantName(enumValue);
            
            // All constants need values when using @JsonValue
            enumConstants.add("    " + enumConstantName + "(\"" + enumValue + "\")");
        }
        
        // Add catch-all for unknown values
        enumConstants.add("    UNKNOWN_TO_SDK(null)");
        
        enumBuilder.append(String.join(",\n", enumConstants));
        
        // Add constructor and @JsonValue method
        enumBuilder.append(";\n\n");
        enumBuilder.append("    private final String value;\n\n");
        enumBuilder.append("    ").append(className).append("(String value) {\n");
        enumBuilder.append("        this.value = value;\n");
        enumBuilder.append("    }\n\n");
        enumBuilder.append("    @JsonValue\n");
        enumBuilder.append("    public String getValue() {\n");
        enumBuilder.append("        return value;\n");
        enumBuilder.append("    }\n\n");
        
        // Add custom deserializer method
        enumBuilder.append("    @JsonCreator\n");
        enumBuilder.append("    public static ").append(className).append(" fromValue(String value) {\n");
        enumBuilder.append("        if (value == null) {\n");
        enumBuilder.append("            return UNKNOWN_TO_SDK;\n");
        enumBuilder.append("        }\n");
        enumBuilder.append("        for (").append(className).append(" item : ").append(className).append(".values()) {\n");
        enumBuilder.append("            if (item != UNKNOWN_TO_SDK && value.equals(item.value)) {\n");
        enumBuilder.append("                return item;\n");
        enumBuilder.append("            }\n");
        enumBuilder.append("        }\n");
        enumBuilder.append("        return UNKNOWN_TO_SDK;\n");
        enumBuilder.append("    }\n");
        enumBuilder.append("}\n");
    }
    
    private void writeToFile(String content, String fileName, String outputDir) throws IOException {
        Path outputPath = Paths.get(outputDir);
        if (!Files.exists(outputPath)) {
            Files.createDirectories(outputPath);
        }
        
        Path filePath = outputPath.resolve(fileName);
        Files.writeString(filePath, content);
    }
    
    private ResolvedDefinition resolveAllOfReference(String refValue, String currentFilename) {
        if (refValue.startsWith("#/definitions/")) {
            // Local reference within the same file
            String definitionName = refValue.substring("#/definitions/".length());
            JsonNode definition = findDefinitionByName(definitionName, currentFilename);
            return definition != null ? new ResolvedDefinition(definition, currentFilename) : null;
        } else if (refValue.startsWith("./") && refValue.contains("#/definitions/")) {
            // External reference to another file
            int hashIndex = refValue.indexOf("#");
            String externalFilename = refValue.substring(2, hashIndex); // Remove "./"
            String definitionName = refValue.substring(hashIndex + "#/definitions/".length());
            JsonNode definition = findDefinitionByName(definitionName, externalFilename);
            return definition != null ? new ResolvedDefinition(definition, externalFilename) : null;
        }
        return null;
    }
    
    private static class ResolvedDefinition {
        private final JsonNode definition;
        private final String filename;
        
        public ResolvedDefinition(JsonNode definition, String filename) {
            this.definition = definition;
            this.filename = filename;
        }
        
        public JsonNode getDefinition() { return definition; }
        public String getFilename() { return filename; }
    }
    
    private JsonNode findDefinitionByName(String definitionName, String filename) {
        // Look for the definition in our loaded definitions
        for (Map.Entry<DefinitionKey, JsonNode> entry : definitions.entrySet()) {
            DefinitionKey key = entry.getKey();
            if (key.definitionKey().equals(definitionName) && key.filename().equals(filename)) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    private void addFieldsFromDefinition(JsonNode definition, List<String> fields, String currentFilename, Set<String> addedFieldNames) {
        // Recursively handle allOf in the referenced definition  
        JsonNode allOf = definition.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (JsonNode allOfItem : allOf) {
                JsonNode ref = allOfItem.get("$ref");
                if (ref != null && ref.isTextual()) {
                    String refValue = ref.asText();
                    ResolvedDefinition resolvedDef = resolveAllOfReference(refValue, currentFilename);
                    if (resolvedDef != null) {
                        addFieldsFromDefinition(resolvedDef.getDefinition(), fields, resolvedDef.getFilename(), addedFieldNames);
                    }
                }
            }
        }
        
        // Add properties from this definition (skip duplicates)
        JsonNode properties = definition.get("properties");
        if (properties != null && properties.isObject()) {
            properties.fieldNames().forEachRemaining(propertyName -> {
                if (shouldIgnoreProperty(propertyName)) {
                    return;
                }
                
                String fieldName = convertToJavaFieldName(propertyName);
                if (addedFieldNames.contains(fieldName)) {
                    return; // Skip duplicate field
                }
                
                JsonNode property = properties.get(propertyName);
                String javaType = getJavaType(property, currentFilename);
                String fieldDeclaration = generateFieldDeclaration(javaType, fieldName, propertyName);
                fields.add(fieldDeclaration);
                addedFieldNames.add(fieldName);
            });
        }
    }
}
