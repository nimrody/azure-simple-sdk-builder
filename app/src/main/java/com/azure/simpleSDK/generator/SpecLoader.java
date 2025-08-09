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

    public Map<String, Operation> loadOperations() throws IOException {
        Map<String, Operation> operations = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            throw new IOException("Path does not exist or is not a directory: " + basePath);
        }

        try (var stream = Files.walk(basePath)) {
            stream.filter(Files::isRegularFile)
                  .filter(file -> file.toString().endsWith(".json"))
                  .forEach(file -> {
                      try {
                          String content = Files.readString(file);
                          JsonNode rootNode = mapper.readTree(content);
                          
                          JsonNode pathsNode = rootNode.get("paths");
                          if (pathsNode != null && pathsNode.isObject()) {
                              extractOperations(pathsNode, operations);
                          }
                      } catch (IOException e) {
                          System.err.println("Error reading file " + file + ": " + e.getMessage());
                      }
                  });
        }

        return operations;
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
                            Operation operation = new Operation(
                                operationId,
                                apiPath,
                                httpMethod.toUpperCase(),
                                operationNode
                            );
                            operations.put(operationId, operation);
                        }
                    }
                });
            }
        });
    }

    public static void main(String[] args) {
        System.out.println("Current working directory: " + System.getProperty("user.dir"));
        String path = "azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/stable/2024-07-01/";
        SpecLoader loader = new SpecLoader(path);
        
        try {
            Map<String, Operation> operationMap = loader.loadOperations();
            System.out.println("Found " + operationMap.size() + " operations:");
            operationMap.values().stream()
                    .sorted((a, b) -> a.operationId().compareTo(b.operationId()))
                    .forEach(op -> System.out.println(op.operationId() + " [" + op.httpMethod() + " " + op.apiPath() + "]"));
        } catch (IOException e) {
            System.err.println("Error loading files: " + e.getMessage());
        }
    }
}