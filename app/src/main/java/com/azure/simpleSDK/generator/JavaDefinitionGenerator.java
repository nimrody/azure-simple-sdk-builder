package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.*;
import java.io.IOException;

public class JavaDefinitionGenerator {
    private static final Pattern REF_PATTERN = Pattern.compile("^(\\./([^#]+)#)?/definitions/(.+)$");
    private final Set<String> duplicateDefinitionNames;
    
    public JavaDefinitionGenerator(Set<String> duplicateDefinitionNames) {
        this.duplicateDefinitionNames = duplicateDefinitionNames != null ? duplicateDefinitionNames : new HashSet<>();
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
                        return "java.util.List<" + itemType + ">";
                    }
                    return "java.util.List<Object>";
                case "object":
                    if (property.has("additionalProperties")) {
                        JsonNode additionalProps = property.get("additionalProperties");
                        if (additionalProps.isBoolean() && additionalProps.asBoolean()) {
                            return "java.util.Map<String, Object>";
                        } else if (additionalProps.isObject()) {
                            String valueType = getJavaType(additionalProps, currentFilename);
                            return "java.util.Map<String, " + valueType + ">";
                        }
                    }
                    return "java.util.Map<String, Object>";
                default:
                    return "Object";
            }
        }
        
        return "Object";
    }
    
    String resolveReference(String ref, String currentFilename) {
        Matcher matcher = REF_PATTERN.matcher(ref);
        if (matcher.matches()) {
            String filename = matcher.group(2);
            String definitionName = matcher.group(3);
            
            if (filename == null) {
                filename = currentFilename;
            }
            
            if (duplicateDefinitionNames.contains(definitionName)) {
                String baseName = filename.replaceAll("\\.json$", "");
                return capitalizeFirstLetter(baseName) + capitalizeFirstLetter(definitionName);
            } else {
                return capitalizeFirstLetter(definitionName);
            }
        }
        
        return "Object";
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
