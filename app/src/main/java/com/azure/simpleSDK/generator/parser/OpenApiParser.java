package com.azure.simpleSDK.generator.parser;

import com.azure.simpleSDK.generator.discovery.SpecificationFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OpenApiParser {
    private static final Logger logger = LoggerFactory.getLogger(OpenApiParser.class);
    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> externalFileCache = new ConcurrentHashMap<>();
    
    public OpenApiParser() {
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Clear the external file cache to free memory.
     * Useful when processing many operations.
     */
    public void clearCache() {
        externalFileCache.clear();
        logger.debug("Cleared external file cache");
    }
    
    /**
     * Get cache statistics for monitoring.
     */
    public int getCacheSize() {
        return externalFileCache.size();
    }
    
    public OperationSpec parseOperation(SpecificationFile specFile, String operationId) {
        try {
            Path specPath = specFile.getPath();
            JsonNode root = objectMapper.readTree(Files.readString(specPath));
            
            JsonNode paths = root.get("paths");
            if (paths == null) {
                logger.warn("No paths found in specification file: {}", specPath);
                return null;
            }
            
            for (Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields(); pathIterator.hasNext(); ) {
                Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
                String pathPattern = pathEntry.getKey();
                JsonNode pathNode = pathEntry.getValue();
                
                for (Iterator<Map.Entry<String, JsonNode>> methodIterator = pathNode.fields(); methodIterator.hasNext(); ) {
                    Map.Entry<String, JsonNode> methodEntry = methodIterator.next();
                    String httpMethod = methodEntry.getKey().toUpperCase();
                    JsonNode operationNode = methodEntry.getValue();
                    
                    JsonNode operationIdNode = operationNode.get("operationId");
                    if (operationIdNode != null && operationId.equals(operationIdNode.asText())) {
                        return parseOperationDetails(pathPattern, httpMethod, operationNode, root, specPath);
                    }
                }
            }
            
            logger.warn("Operation {} not found in specification file: {}", operationId, specPath);
            return null;
            
        } catch (IOException e) {
            logger.error("Failed to parse specification file: {}", specFile.getPath(), e);
            return null;
        }
    }
    
    private OperationSpec parseOperationDetails(String pathPattern, String httpMethod, JsonNode operationNode, JsonNode root, Path specPath) {
        String operationId = operationNode.get("operationId").asText();
        String description = operationNode.has("description") ? operationNode.get("description").asText() : "";
        
        List<Parameter> parameters = parseParameters(operationNode.get("parameters"), root, specPath);
        Map<String, Response> responses = parseResponses(operationNode.get("responses"), root, specPath);
        
        return new OperationSpec(operationId, httpMethod, pathPattern, parameters, responses, description);
    }
    
    private List<Parameter> parseParameters(JsonNode parametersNode, JsonNode root, Path specPath) {
        if (parametersNode == null || !parametersNode.isArray()) {
            return new ArrayList<>();
        }
        
        List<Parameter> parameters = new ArrayList<>();
        for (JsonNode paramNode : parametersNode) {
            JsonNode resolvedParam = resolveReference(paramNode, root, specPath);
            if (resolvedParam != null) {
                String name = resolvedParam.get("name").asText();
                String in = resolvedParam.get("in").asText();
                boolean required = resolvedParam.has("required") && resolvedParam.get("required").asBoolean();
                String type = extractType(resolvedParam, root, specPath);
                String description = resolvedParam.has("description") ? resolvedParam.get("description").asText() : "";
                
                parameters.add(new Parameter(name, in, required, type, description));
            }
        }
        
        return parameters;
    }
    
    private Map<String, Response> parseResponses(JsonNode responsesNode, JsonNode root, Path specPath) {
        if (responsesNode == null) {
            return new HashMap<>();
        }
        
        Map<String, Response> responses = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> iterator = responsesNode.fields(); iterator.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            String statusCode = entry.getKey();
            JsonNode responseNode = resolveReference(entry.getValue(), root, specPath);
            
            if (responseNode != null) {
                String description = responseNode.has("description") ? responseNode.get("description").asText() : "";
                String schemaType = extractSchemaType(responseNode.get("schema"), root, specPath);
                
                responses.put(statusCode, new Response(statusCode, description, schemaType));
            }
        }
        
        return responses;
    }
    
    private JsonNode resolveReference(JsonNode node, JsonNode root) {
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            return resolveJsonPointer(ref, root);
        }
        return node;
    }
    
    private JsonNode resolveReference(JsonNode node, JsonNode root, Path baseSpecPath) {
        if (node.has("$ref")) {
            String ref = node.get("$ref").asText();
            return resolveJsonPointer(ref, root, baseSpecPath);
        }
        return node;
    }
    
    private JsonNode resolveJsonPointer(String ref, JsonNode root) {
        return resolveJsonPointer(ref, root, null);
    }
    
    private JsonNode resolveJsonPointer(String ref, JsonNode root, Path baseSpecPath) {
        if (ref.startsWith("#/")) {
            // Internal reference - resolve within current document
            return resolveInternalReference(ref, root);
        } else if (ref.contains("#")) {
            // External reference - load external file and resolve pointer
            return resolveExternalReference(ref, baseSpecPath);
        }
        
        logger.warn("Unsupported reference format: {}", ref);
        return null;
    }
    
    private JsonNode resolveInternalReference(String ref, JsonNode root) {
        String[] parts = ref.substring(2).split("/");
        JsonNode current = root;
        
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            current = current.get(part);
        }
        
        return current;
    }
    
    private JsonNode resolveExternalReference(String ref, Path baseSpecPath) {
        if (baseSpecPath == null) {
            logger.warn("Cannot resolve external reference without base path: {}", ref);
            return null;
        }
        
        try {
            // Split reference into file path and JSON pointer
            String[] parts = ref.split("#", 2);
            String filePath = parts[0];
            String jsonPointer = parts.length > 1 ? "#" + parts[1] : "#/";
            
            // Resolve relative path
            Path externalFilePath = baseSpecPath.getParent().resolve(filePath).normalize();
            String cacheKey = externalFilePath.toString();
            
            // Load external file (with caching)
            JsonNode externalRoot = externalFileCache.computeIfAbsent(cacheKey, key -> {
                try {
                    if (!Files.exists(externalFilePath)) {
                        logger.warn("External reference file not found: {}", externalFilePath);
                        return null;
                    }
                    
                    logger.info("Loading external specification file: {}", externalFilePath);
                    String content = Files.readString(externalFilePath);
                    return objectMapper.readTree(content);
                } catch (IOException e) {
                    logger.error("Failed to load external reference file: {}", externalFilePath, e);
                    return null;
                }
            });
            
            if (externalRoot == null) {
                return null;
            }
            
            // Resolve JSON pointer in external document
            if (jsonPointer.startsWith("#/")) {
                return resolveInternalReference(jsonPointer, externalRoot);
            }
            
            return externalRoot;
            
        } catch (Exception e) {
            logger.error("Error resolving external reference: {}", ref, e);
            return null;
        }
    }
    
    private String extractType(JsonNode paramNode, JsonNode root, Path specPath) {
        if (paramNode.has("type")) {
            return paramNode.get("type").asText();
        } else if (paramNode.has("schema")) {
            return extractSchemaType(paramNode.get("schema"), root, specPath);
        }
        return "string"; // default
    }
    
    private String extractSchemaType(JsonNode schemaNode, JsonNode root, Path specPath) {
        if (schemaNode == null) {
            return null;
        }
        
        JsonNode resolvedSchema = resolveReference(schemaNode, root, specPath);
        if (resolvedSchema == null) {
            return null;
        }
        
        if (resolvedSchema.has("type")) {
            String type = resolvedSchema.get("type").asText();
            if ("array".equals(type) && resolvedSchema.has("items")) {
                String itemType = extractSchemaType(resolvedSchema.get("items"), root, specPath);
                return itemType != null ? "List<" + itemType + ">" : "List<Object>";
            }
            return mapOpenApiTypeToJava(type);
        } else if (resolvedSchema.has("$ref")) {
            // Extract model name from reference
            String ref = resolvedSchema.get("$ref").asText();
            return extractModelNameFromRef(ref);
        }
        
        return "Object"; // fallback
    }
    
    private String mapOpenApiTypeToJava(String openApiType) {
        return switch (openApiType) {
            case "string" -> "String";
            case "integer" -> "Integer";
            case "number" -> "Double";
            case "boolean" -> "Boolean";
            case "array" -> "List";
            case "object" -> "Map<String, Object>";
            default -> "Object";
        };
    }
    
    private String extractModelNameFromRef(String ref) {
        int lastSlash = ref.lastIndexOf('/');
        if (lastSlash >= 0) {
            return ref.substring(lastSlash + 1);
        }
        return ref;
    }
}