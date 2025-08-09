package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class SpecLoader {
    private final Path basePath;

    public SpecLoader(String path) {
        this.basePath = Paths.get(path);
    }

    public SpecResult loadSpecs() throws IOException {
        Map<String, Operation> operations = new HashMap<>();
        Map<DefinitionKey, JsonNode> definitions = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            throw new IOException("Path does not exist or is not a directory: " + basePath);
        }

        try (var stream = Files.walk(basePath)) {
            stream.filter(Files::isRegularFile)
                  .filter(file -> file.toString().endsWith(".json"))
                  .forEach(file -> {
                      try {
                          String filename = file.getFileName().toString();
                          String content = Files.readString(file);
                          JsonNode rootNode = mapper.readTree(content);
                          
                          JsonNode pathsNode = rootNode.get("paths");
                          if (pathsNode != null && pathsNode.isObject()) {
                              extractOperations(pathsNode, operations);
                          }
                          
                          JsonNode definitionsNode = rootNode.get("definitions");
                          if (definitionsNode != null && definitionsNode.isObject()) {
                              extractDefinitions(definitionsNode, filename, definitions);
                          }
                      } catch (IOException e) {
                          System.err.println("Error reading file " + file + ": " + e.getMessage());
                      }
                  });
        }

        return new SpecResult(operations, definitions);
    }

    private void extractOperations(JsonNode pathsNode, Map<String, Operation> operations) {
        pathsNode.fieldNames().forEachRemaining(apiPath -> {
            JsonNode pathNode = pathsNode.get(apiPath);
            if (pathNode.isObject()) {
                pathNode.fieldNames().forEachRemaining(httpMethod -> {
                    JsonNode operationNode = pathNode.get(httpMethod);
                    if (operationNode.isObject()) {
                        JsonNode operationIdNode = operationNode.get("operationId");
                        if (operationIdNode != null && operationIdNode.isTextual()) {
                            String operationId = operationIdNode.asText();
                            Map<String, String> responseSchemas = extractResponseSchemas(operationNode);
                            Operation operation = new Operation(
                                operationId,
                                apiPath,
                                httpMethod.toUpperCase(),
                                operationNode,
                                responseSchemas
                            );
                            operations.put(operationId, operation);
                        }
                    }
                });
            }
        });
    }

    private void extractDefinitions(JsonNode definitionsNode, String filename, Map<DefinitionKey, JsonNode> definitions) {
        definitionsNode.fieldNames().forEachRemaining(definitionKey -> {
            JsonNode definitionValue = definitionsNode.get(definitionKey);
            DefinitionKey key = new DefinitionKey(filename, definitionKey);
            definitions.put(key, definitionValue);
        });
    }

    private Map<String, String> extractResponseSchemas(JsonNode operationNode) {
        Map<String, String> responseSchemas = new HashMap<>();
        JsonNode responsesNode = operationNode.get("responses");
        if (responsesNode != null && responsesNode.isObject()) {
            responsesNode.fieldNames().forEachRemaining(responseCode -> {
                JsonNode responseNode = responsesNode.get(responseCode);
                if (responseNode.isObject()) {
                    JsonNode schemaNode = responseNode.get("schema");
                    if (schemaNode != null && schemaNode.isObject()) {
                        JsonNode refNode = schemaNode.get("$ref");
                        if (refNode != null && refNode.isTextual()) {
                            responseSchemas.put(responseCode, refNode.asText());
                        }
                    }
                }
            });
        }
        return responseSchemas;
    }

    public static void main(String[] args) {
        System.out.println("Current working directory: " + System.getProperty("user.dir"));
        String path = "azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/stable/2024-07-01/";
        SpecLoader loader = new SpecLoader(path);
        
        try {
            SpecResult result = loader.loadSpecs();
            System.out.println("Found " + result.operations().size() + " operations:");
            result.operations().values().stream()
                    .sorted((a, b) -> a.operationId().compareTo(b.operationId()))
                    .forEach(op -> {
                        System.out.println(op.operationId() + " [" + op.httpMethod() + " " + op.apiPath() + "]");
                        if (!op.responseSchemas().isEmpty()) {
                            op.responseSchemas().forEach((code, schema) -> 
                                System.out.println("  Response " + code + ": " + schema));
                        }
                    });
            
            System.out.println("\nFound " + result.definitions().size() + " definitions:");
            result.definitions().keySet().stream()
                    .sorted((a, b) -> {
                        int fileCompare = a.filename().compareTo(b.filename());
                        return fileCompare != 0 ? fileCompare : a.definitionKey().compareTo(b.definitionKey());
                    })
                    .forEach(key -> System.out.println(key.filename() + " -> " + key.definitionKey()));
        } catch (IOException e) {
            System.err.println("Error loading files: " + e.getMessage());
        }
    }
}