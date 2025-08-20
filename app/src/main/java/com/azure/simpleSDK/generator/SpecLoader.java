package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpecLoader {
    private final Path basePath;

    public SpecLoader(String path) {
        this.basePath = Paths.get(path);
    }

    public SpecResult loadSpecs() throws IOException {
        Map<String, Operation> operations = new HashMap<>();
        Map<DefinitionKey, JsonNode> definitions = new HashMap<>();
        Set<String> loadedFiles = new HashSet<>();
        Set<String> externalRefsToLoad = new HashSet<>();
        ObjectMapper mapper = new ObjectMapper();
        
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            throw new IOException("Path does not exist or is not a directory: " + basePath);
        }

        // Phase 1: Load initial files and collect external references
        try (var stream = Files.walk(basePath)) {
            stream.filter(Files::isRegularFile)
                  .filter(file -> file.toString().endsWith(".json"))
                  .forEach(file -> {
                      try {
                          String filename = file.getFileName().toString();
                          String content = Files.readString(file);
                          JsonNode rootNode = mapper.readTree(content);
                          
                          // Track loaded file
                          loadedFiles.add(file.toString());
                          
                          // Extract operations
                          JsonNode pathsNode = rootNode.get("paths");
                          if (pathsNode != null && pathsNode.isObject()) {
                              extractOperations(pathsNode, operations);
                          }
                          
                          // Extract definitions
                          JsonNode definitionsNode = rootNode.get("definitions");
                          if (definitionsNode != null && definitionsNode.isObject()) {
                              extractDefinitions(definitionsNode, filename, content, definitions);
                          }
                          
                          // Collect external references
                          collectExternalReferences(rootNode, file.getParent(), externalRefsToLoad);
                          
                      } catch (IOException e) {
                          System.err.println("Error reading file " + file + ": " + e.getMessage());
                      }
                  });
        }
        
        // Phase 2: Load external referenced files
        loadExternalReferences(externalRefsToLoad, loadedFiles, definitions, mapper);

        return new SpecResult(operations, definitions);
    }

    public static Set<String> findDuplicateDefinitionNames(Map<DefinitionKey, JsonNode> definitions) {
        Map<String, Integer> definitionCounts = new HashMap<>();
        
        for (DefinitionKey key : definitions.keySet()) {
            definitionCounts.merge(key.definitionKey(), 1, Integer::sum);
        }
        
        return definitionCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
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

    private void extractDefinitions(JsonNode definitionsNode, String filename, String content, Map<DefinitionKey, JsonNode> definitions) {
        definitionsNode.fieldNames().forEachRemaining(definitionKey -> {
            JsonNode definitionValue = definitionsNode.get(definitionKey);
            int lineNumber = findDefinitionLineNumber(content, definitionKey);
            DefinitionKey key = new DefinitionKey(filename, definitionKey, lineNumber);
            definitions.put(key, definitionValue);
        });
    }
    
    private int findDefinitionLineNumber(String content, String definitionKey) {
        String[] lines = content.split("\n");
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(definitionKey) + "\"\\s*:");
        
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find()) {
                return i + 1; // Line numbers are 1-based
            }
        }
        return -1; // Not found
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
        
        // Generate separate SDKs for each service
        generateSeparateSDKs();
    }
    
    public static void generateSeparateSDKs() {
        // Define service specifications
        Map<String, ServiceSpec> serviceSpecs = new HashMap<>();
        
        serviceSpecs.put("network", new ServiceSpec(
            "Network",
            "azure-rest-api-specs/specification/network/resource-manager/Microsoft.Network/stable/2024-07-01/",
            "sdk/src/main/java/com/azure/simpleSDK/network/models",
            "sdk/src/main/java/com/azure/simpleSDK/network/client",
            "com.azure.simpleSDK.network.models",
            "com.azure.simpleSDK.network.client",
            "AzureNetworkClient"
        ));
        
        serviceSpecs.put("compute", new ServiceSpec(
            "Compute", 
            "azure-rest-api-specs/specification/compute/resource-manager/Microsoft.Compute/ComputeRP/stable/2024-11-01/",
            "sdk/src/main/java/com/azure/simpleSDK/compute/models",
            "sdk/src/main/java/com/azure/simpleSDK/compute/client", 
            "com.azure.simpleSDK.compute.models",
            "com.azure.simpleSDK.compute.client",
            "AzureComputeClient"
        ));
        
        // Generate SDK for each service
        for (Map.Entry<String, ServiceSpec> entry : serviceSpecs.entrySet()) {
            String serviceName = entry.getKey();
            ServiceSpec spec = entry.getValue();
            
            System.out.println("\n=== Generating " + spec.displayName + " SDK ===");
            generateServiceSDK(spec);
        }
    }
    
    private static class ServiceSpec {
        final String displayName;
        final String specsPath;
        final String modelsOutputDir;
        final String clientOutputDir;
        final String modelsPackage;
        final String clientPackage;
        final String clientClassName;
        
        ServiceSpec(String displayName, String specsPath, String modelsOutputDir, String clientOutputDir, 
                   String modelsPackage, String clientPackage, String clientClassName) {
            this.displayName = displayName;
            this.specsPath = specsPath;
            this.modelsOutputDir = modelsOutputDir;
            this.clientOutputDir = clientOutputDir;
            this.modelsPackage = modelsPackage;
            this.clientPackage = clientPackage;
            this.clientClassName = clientClassName;
        }
    }
    
    private static void generateServiceSDK(ServiceSpec serviceSpec) {
        SpecLoader loader = new SpecLoader(serviceSpec.specsPath);
        
        try {
            SpecResult result = loader.loadSpecs();
            System.out.println("Found " + result.operations().size() + " operations");
            System.out.println("Found " + result.definitions().size() + " definitions");
            
            // Find duplicate definition names within this service
            Set<String> duplicateNames = findDuplicateDefinitionNames(result.definitions());
            
            System.out.println("Duplicate definitions within service:");
            if (duplicateNames.isEmpty()) {
                System.out.println("No duplicate definition names found.");
            } else {
                for (String duplicateName : duplicateNames) {
                    System.out.println("Definition '" + duplicateName + "' appears in:");
                    result.definitions().entrySet().stream()
                            .filter(entry -> entry.getKey().definitionKey().equals(duplicateName))
                            .forEach(entry -> System.out.println("  - " + entry.getKey().filename()));
                }
            }
            
            // Generate models with service-specific package
            System.out.println("Generating Java records to: " + serviceSpec.modelsOutputDir);
            JavaDefinitionGenerator generator = new JavaDefinitionGenerator(duplicateNames, result.definitions(), serviceSpec.modelsPackage);
            int generatedCount = 0;
            
            for (Map.Entry<DefinitionKey, JsonNode> entry : result.definitions().entrySet()) {
                try {
                    String className = generator.getClassName(entry.getKey());
                    generator.writeRecordToFile(entry.getKey(), entry.getValue(), serviceSpec.modelsOutputDir);
                    System.out.println("Generated " + className + ".java from " + 
                                     entry.getKey().filename() + " -> " + entry.getKey().definitionKey());
                    generatedCount++;
                } catch (IOException e) {
                    System.err.println("Error generating file for " + entry.getKey() + ": " + e.getMessage());
                }
            }
            
            // Generate inline enums as separate files
            Map<String, JsonNode> inlineEnums = generator.getInlineEnums();
            int inlineEnumCount = 0;
            for (Map.Entry<String, JsonNode> enumEntry : inlineEnums.entrySet()) {
                try {
                    String enumName = enumEntry.getKey();
                    JsonNode enumDefinition = enumEntry.getValue();
                    generator.writeInlineEnumToFile(enumName, enumDefinition, serviceSpec.modelsOutputDir);
                    System.out.println("Generated " + enumName + ".java (inline enum)");
                    inlineEnumCount++;
                } catch (IOException e) {
                    System.err.println("Error generating inline enum file for " + enumEntry.getKey() + ": " + e.getMessage());
                }
            }
            
            System.out.println("Generated " + generatedCount + " Java record files");
            if (inlineEnumCount > 0) {
                System.out.println("Generated " + inlineEnumCount + " inline enum files");
            }
            
            // Generate service-specific client
            System.out.println("Generating " + serviceSpec.displayName + " client:");
            OperationGenerator operationGenerator = new OperationGenerator(result.operations(), duplicateNames, 
                    result.definitions(), serviceSpec.modelsPackage, serviceSpec.clientPackage, serviceSpec.clientClassName);
            
            try {
                operationGenerator.generateAzureClient(serviceSpec.clientOutputDir);
                System.out.println("Generated " + serviceSpec.clientClassName + ".java in " + serviceSpec.clientOutputDir);
            } catch (IOException e) {
                System.err.println("Error generating " + serviceSpec.displayName + " client: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("Error loading " + serviceSpec.displayName + " specifications: " + e.getMessage());
        }
    }
    
    public static void generateSDK(String specsPath, String modelsOutputDir, String clientOutputDir) {
        SpecLoader loader = new SpecLoader(specsPath);
        
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
            
            System.out.println("\nDuplicate definitions across files:");
            Set<String> duplicateNames = findDuplicateDefinitionNames(result.definitions());
            if (duplicateNames.isEmpty()) {
                System.out.println("No duplicate definition names found.");
            } else {
                for (String duplicateName : duplicateNames) {
                    System.out.println("Definition '" + duplicateName + "' appears in:");
                    result.definitions().entrySet().stream()
                            .filter(entry -> entry.getKey().definitionKey().equals(duplicateName))
                            .forEach(entry -> System.out.println("  - " + entry.getKey().filename()));
                }
            }
            
            System.out.println("\nGenerating Java records to SDK project:");
            
            JavaDefinitionGenerator generator = new JavaDefinitionGenerator(duplicateNames, result.definitions());
            int generatedCount = 0;
            
            for (Map.Entry<DefinitionKey, JsonNode> entry : result.definitions().entrySet()) {
                try {
                    String className = generator.getClassName(entry.getKey());
                    generator.writeRecordToFile(entry.getKey(), entry.getValue(), modelsOutputDir);
                    System.out.println("Generated " + className + ".java from " + 
                                     entry.getKey().filename() + " -> " + entry.getKey().definitionKey());
                    generatedCount++;
                } catch (IOException e) {
                    System.err.println("Error generating file for " + entry.getKey() + ": " + e.getMessage());
                }
            }
            
            // Generate inline enums as separate files
            Map<String, JsonNode> inlineEnums = generator.getInlineEnums();
            int inlineEnumCount = 0;
            for (Map.Entry<String, JsonNode> enumEntry : inlineEnums.entrySet()) {
                try {
                    String enumName = enumEntry.getKey();
                    JsonNode enumDefinition = enumEntry.getValue();
                    generator.writeInlineEnumToFile(enumName, enumDefinition, modelsOutputDir);
                    System.out.println("Generated " + enumName + ".java (inline enum)");
                    inlineEnumCount++;
                } catch (IOException e) {
                    System.err.println("Error generating inline enum file for " + enumEntry.getKey() + ": " + e.getMessage());
                }
            }
            
            System.out.println("\nGenerated " + generatedCount + " Java record files in " + modelsOutputDir);
            if (inlineEnumCount > 0) {
                System.out.println("Generated " + inlineEnumCount + " inline enum files in " + modelsOutputDir);
            }
            
            // Generate Azure client with GET operations
            System.out.println("\nGenerating Azure SDK client with GET operations:");
            OperationGenerator operationGenerator = new OperationGenerator(result.operations(), duplicateNames, result.definitions());
            
            try {
                operationGenerator.generateAzureClient(clientOutputDir);
                System.out.println("Generated AzureSimpleSDKClient.java in " + clientOutputDir);
            } catch (IOException e) {
                System.err.println("Error generating Azure client: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("Error loading files: " + e.getMessage());
        }
    }
    
    public static void generateCombinedSDK(List<String> specsPaths, String modelsOutputDir, String clientOutputDir) {
        Map<String, Operation> combinedOperations = new HashMap<>();
        Map<DefinitionKey, JsonNode> combinedDefinitions = new HashMap<>();
        
        // Load specifications from all paths and combine them
        for (String specsPath : specsPaths) {
            System.out.println("\n=== Loading specifications from: " + specsPath + " ===");
            SpecLoader loader = new SpecLoader(specsPath);
            
            try {
                SpecResult result = loader.loadSpecs();
                System.out.println("Found " + result.operations().size() + " operations");
                System.out.println("Found " + result.definitions().size() + " definitions");
                
                // Merge operations (operations have unique IDs, so direct merge is safe)
                combinedOperations.putAll(result.operations());
                
                // Merge definitions (may have duplicates, but our duplicate handling will manage this)
                combinedDefinitions.putAll(result.definitions());
                
            } catch (IOException e) {
                System.err.println("Error loading specifications from " + specsPath + ": " + e.getMessage());
                continue;
            }
        }
        
        System.out.println("\n=== Combined Results ===");
        System.out.println("Total operations: " + combinedOperations.size());
        System.out.println("Total definitions: " + combinedDefinitions.size());
        
        // Find duplicate definition names across all loaded specifications
        Set<String> duplicateNames = findDuplicateDefinitionNames(combinedDefinitions);
        
        System.out.println("\nDuplicate definitions across all files:");
        if (duplicateNames.isEmpty()) {
            System.out.println("No duplicate definition names found.");
        } else {
            for (String duplicateName : duplicateNames) {
                System.out.println("Definition '" + duplicateName + "' appears in:");
                combinedDefinitions.entrySet().stream()
                        .filter(entry -> entry.getKey().definitionKey().equals(duplicateName))
                        .forEach(entry -> System.out.println("  - " + entry.getKey().filename()));
            }
        }
        
        // Generate models from combined definitions
        System.out.println("\nGenerating Java records to SDK project:");
        JavaDefinitionGenerator generator = new JavaDefinitionGenerator(duplicateNames, combinedDefinitions);
        int generatedCount = 0;
        
        for (Map.Entry<DefinitionKey, JsonNode> entry : combinedDefinitions.entrySet()) {
            try {
                String className = generator.getClassName(entry.getKey());
                generator.writeRecordToFile(entry.getKey(), entry.getValue(), modelsOutputDir);
                System.out.println("Generated " + className + ".java from " + 
                                 entry.getKey().filename() + " -> " + entry.getKey().definitionKey());
                generatedCount++;
            } catch (IOException e) {
                System.err.println("Error generating file for " + entry.getKey() + ": " + e.getMessage());
            }
        }
        
        // Generate inline enums as separate files
        Map<String, JsonNode> inlineEnums = generator.getInlineEnums();
        int inlineEnumCount = 0;
        for (Map.Entry<String, JsonNode> enumEntry : inlineEnums.entrySet()) {
            try {
                String enumName = enumEntry.getKey();
                JsonNode enumDefinition = enumEntry.getValue();
                generator.writeInlineEnumToFile(enumName, enumDefinition, modelsOutputDir);
                System.out.println("Generated " + enumName + ".java (inline enum)");
                inlineEnumCount++;
            } catch (IOException e) {
                System.err.println("Error generating inline enum file for " + enumEntry.getKey() + ": " + e.getMessage());
            }
        }
        
        System.out.println("\nGenerated " + generatedCount + " Java record files in " + modelsOutputDir);
        if (inlineEnumCount > 0) {
            System.out.println("Generated " + inlineEnumCount + " inline enum files in " + modelsOutputDir);
        }
        
        // Generate Azure client with GET operations from all combined operations
        System.out.println("\nGenerating Azure SDK client with GET operations:");
        OperationGenerator operationGenerator = new OperationGenerator(combinedOperations, duplicateNames, combinedDefinitions);
        
        try {
            operationGenerator.generateAzureClient(clientOutputDir);
            System.out.println("Generated AzureSimpleSDKClient.java in " + clientOutputDir);
        } catch (IOException e) {
            System.err.println("Error generating Azure client: " + e.getMessage());
        }
    }
    
    private void collectExternalReferences(JsonNode rootNode, Path currentFileDir, Set<String> externalRefsToLoad) {
        collectExternalReferencesRecursive(rootNode, currentFileDir, externalRefsToLoad);
    }
    
    private void collectExternalReferencesRecursive(JsonNode node, Path currentFileDir, Set<String> externalRefsToLoad) {
        if (node.isObject()) {
            JsonNode refNode = node.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                String refValue = refNode.asText();
                if (refValue.startsWith("./") || refValue.startsWith("../")) {
                    // External reference - resolve the file path
                    String filePath = refValue.contains("#") ? refValue.split("#")[0] : refValue;
                    try {
                        Path resolvedPath = currentFileDir.resolve(filePath).normalize();
                        externalRefsToLoad.add(resolvedPath.toString());
                    } catch (Exception e) {
                        System.err.println("Error resolving external reference: " + refValue);
                    }
                }
            }
            
            // Recursively check all object properties
            node.fieldNames().forEachRemaining(fieldName -> {
                collectExternalReferencesRecursive(node.get(fieldName), currentFileDir, externalRefsToLoad);
            });
        } else if (node.isArray()) {
            // Recursively check all array elements
            for (JsonNode arrayElement : node) {
                collectExternalReferencesRecursive(arrayElement, currentFileDir, externalRefsToLoad);
            }
        }
    }
    
    private void loadExternalReferences(Set<String> externalRefsToLoad, Set<String> loadedFiles, 
                                      Map<DefinitionKey, JsonNode> definitions, ObjectMapper mapper) {
        Set<String> processedExternal = new HashSet<>();
        
        for (String externalFilePath : externalRefsToLoad) {
            loadExternalFile(externalFilePath, loadedFiles, definitions, mapper, processedExternal);
        }
    }
    
    private void loadExternalFile(String filePath, Set<String> loadedFiles, 
                                Map<DefinitionKey, JsonNode> definitions, ObjectMapper mapper,
                                Set<String> processedExternal) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path) || !Files.isRegularFile(path) || processedExternal.contains(filePath)) {
                return;
            }
            
            processedExternal.add(filePath);
            
            String filename = path.getFileName().toString();
            
            // Skip if already loaded by full path (avoid duplicates)
            // Note: We use full path instead of just filename to allow different services 
            // to have files with same name but different content
            if (loadedFiles.contains(filePath)) {
                return;
            }
            
            String content = Files.readString(path);
            JsonNode rootNode = mapper.readTree(content);
            
            // Extract definitions from external file
            JsonNode definitionsNode = rootNode.get("definitions");
            if (definitionsNode != null && definitionsNode.isObject()) {
                extractDefinitions(definitionsNode, filename, content, definitions);
                loadedFiles.add(filePath);  // Use full path instead of just filename
                System.out.println("Loaded external file: " + filename);
            }
            
            // Look for more external references in this file
            Set<String> moreExternalRefs = new HashSet<>();
            collectExternalReferences(rootNode, path.getParent(), moreExternalRefs);
            
            // Recursively load newly found external references
            for (String newExternalRef : moreExternalRefs) {
                if (!processedExternal.contains(newExternalRef)) {
                    loadExternalFile(newExternalRef, loadedFiles, definitions, mapper, processedExternal);
                }
            }
            
        } catch (IOException e) {
            System.err.println("Error loading external file " + filePath + ": " + e.getMessage());
        }
    }
}