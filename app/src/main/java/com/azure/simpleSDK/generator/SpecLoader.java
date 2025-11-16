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

/**
 * Loads and processes Azure REST API specifications from OpenAPI JSON files.
 * 
 * This class performs a two-phase loading process:
 * 1. Phase 1: Scan all JSON files in the specified directory to extract operations and definitions
 * 2. Phase 2: Load external referenced files to resolve cross-file dependencies
 * 
 * All file paths are normalized to be relative to the azure-rest-api-specs/ root directory
 * to ensure consistent referencing across different services and external files.
 */
public class SpecLoader {
    /** The base directory to scan for OpenAPI specification files */
    private final Path basePath;
    /** The root directory of azure-rest-api-specs (found by walking up the directory tree) */
    private final Path azureSpecsRoot;

    /**
     * Creates a new SpecLoader for the specified directory.
     * 
     * @param path The directory path containing OpenAPI JSON files to load
     * @throws IllegalStateException if azure-rest-api-specs root directory cannot be found
     */
    public SpecLoader(String path) {
        this.basePath = Paths.get(path);
        // Find the azure-rest-api-specs root directory
        this.azureSpecsRoot = findAzureSpecsRoot(basePath);
    }
    
    /**
     * Finds the azure-rest-api-specs root directory by walking up the path hierarchy.
     * 
     * @param currentPath The starting path to search from
     * @return The absolute path to the azure-rest-api-specs directory
     * @throws IllegalStateException if the azure-rest-api-specs directory is not found
     */
    private Path findAzureSpecsRoot(Path currentPath) {
        Path path = currentPath.toAbsolutePath();
        while (path != null) {
            if (path.getFileName() != null && path.getFileName().toString().equals("azure-rest-api-specs")) {
                return path;
            }
            path = path.getParent();
        }
        throw new IllegalStateException("Could not find azure-rest-api-specs root directory from path: " + currentPath.toAbsolutePath());
    }
    
    /**
     * Converts an absolute path to a relative path from azure-rest-api-specs/
     * @param absolutePath The absolute path to convert
     * @return The relative path from azure-rest-api-specs/ (using forward slashes)
     * @throws IllegalArgumentException if the path cannot be relativized
     */
    private String getRelativePathFromSpecsRoot(Path absolutePath) {
        try {
            Path normalizedAbsolute = absolutePath.toAbsolutePath().normalize();
            Path normalizedRoot = azureSpecsRoot.toAbsolutePath().normalize();
            
            if (!normalizedAbsolute.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("Path " + normalizedAbsolute + " is not under azure-rest-api-specs root: " + normalizedRoot);
            }
            
            Path relativePath = normalizedRoot.relativize(normalizedAbsolute);
            return relativePath.toString().replace('\\', '/'); // Normalize to forward slashes
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to relativize path " + absolutePath + " from azure-rest-api-specs root " + azureSpecsRoot, e);
        }
    }

    /**
     * Loads Azure REST API specifications from OpenAPI JSON files in a two-phase process.
     * 
     * Phase 1: Scans all JSON files in the base directory and extracts:
     * - Operations from the 'paths' section (HTTP methods and endpoints)
     * - Model definitions from the 'definitions' section  
     * - External file references for phase 2 loading
     * 
     * Phase 2: Recursively loads external referenced files to resolve cross-file dependencies
     * 
     * @return SpecResult containing all loaded operations and definitions with their metadata
     * @throws IOException if files cannot be read or parsed
     */
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
                          // Use relative path from azure-rest-api-specs root for consistency
                          String relativePath = getRelativePathFromSpecsRoot(file);
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
                              extractDefinitions(definitionsNode, relativePath, content, definitions);
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

    /**
     * Extracts REST API operations from the OpenAPI 'paths' section.
     * 
     * Input: OpenAPI paths node containing endpoint definitions
     * Output: Populates operations map with Operation objects containing:
     * - operationId (unique identifier)
     * - API path template (e.g., "/subscriptions/{subscriptionId}/...")
     * - HTTP method (GET, POST, PUT, DELETE, etc.)
     * - Complete operation specification
     * - Response schema mappings for type generation
     * 
     * @param pathsNode The 'paths' section from an OpenAPI specification
     * @param operations Map to populate with extracted operations (keyed by operationId)
     */
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

    /**
     * Extracts model definitions from the OpenAPI 'definitions' section.
     * 
     * Input: OpenAPI definitions node containing model schemas
     * Output: Populates definitions map with DefinitionKey -> JsonNode mappings
     * 
     * Each DefinitionKey contains:
     * - filename: relative path from azure-rest-api-specs/ 
     * - definitionKey: the model name (e.g., "VirtualMachine")
     * - lineNumber: source line number for traceability
     * 
     * @param definitionsNode The 'definitions' section from an OpenAPI specification
     * @param filename The relative path of the source file from azure-rest-api-specs/
     * @param content The complete file content (used for line number calculation)
     * @param definitions Map to populate with extracted definitions
     */
    private void extractDefinitions(JsonNode definitionsNode, String filename, String content, Map<DefinitionKey, JsonNode> definitions) {
        definitionsNode.fieldNames().forEachRemaining(definitionKey -> {
            JsonNode definitionValue = definitionsNode.get(definitionKey);
            int lineNumber = findDefinitionLineNumber(content, definitionKey);
            DefinitionKey key = new DefinitionKey(filename, definitionKey, lineNumber);
            definitions.put(key, definitionValue);
        });
    }
    
    /**
     * Finds the line number where a specific definition appears in the source file.
     * 
     * Uses regex pattern matching to locate the definition key in the JSON content.
     * This enables source traceability for generated code comments.
     * 
     * @param content The complete file content to search
     * @param definitionKey The definition name to locate (e.g., "VirtualMachine")
     * @return The 1-based line number where the definition is found, or 0 if not found
     */
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
        
        serviceSpecs.put("resources", new ServiceSpec(
            "Resources",
            "azure-rest-api-specs/specification/resources/resource-manager/Microsoft.Resources/stable/2022-12-01/",
            "sdk/src/main/java/com/azure/simpleSDK/resources/models",
            "sdk/src/main/java/com/azure/simpleSDK/resources/client",
            "com.azure.simpleSDK.resources.models",
            "com.azure.simpleSDK.resources.client",
            "AzureResourcesClient"
        ));
        
        // Generate SDK for each service
        for (Map.Entry<String, ServiceSpec> entry : serviceSpecs.entrySet()) {
            String serviceName = entry.getKey();
            ServiceSpec spec = entry.getValue();
            
            System.out.println("\n=== Generating " + spec.displayName + " SDK ===");
            generateServiceSDK(spec);
        }
    }
    
    /**
     * Context object containing all parameters needed for code generation.
     * This consolidates the common parameters used across all generation methods.
     */
    private static class CodeGenerationContext {
        final Map<String, Operation> operations;
        final Map<DefinitionKey, JsonNode> definitions;
        final Set<String> duplicateNames;
        final String modelsOutputDir;
        final String clientOutputDir;
        final String modelsPackage;
        final String clientPackage;
        final String clientClassName;
        final String apiVersion;
        final String displayName;
        
        CodeGenerationContext(Map<String, Operation> operations, Map<DefinitionKey, JsonNode> definitions,
                             String modelsOutputDir, String clientOutputDir, String modelsPackage,
                             String clientPackage, String clientClassName, String apiVersion, String displayName) {
            this.operations = operations;
            this.definitions = definitions;
            this.duplicateNames = findDuplicateDefinitionNames(definitions);
            this.modelsOutputDir = modelsOutputDir;
            this.clientOutputDir = clientOutputDir;
            this.modelsPackage = modelsPackage;
            this.clientPackage = clientPackage;
            this.clientClassName = clientClassName;
            this.apiVersion = apiVersion;
            this.displayName = displayName;
        }
    }

    /**
     * Configuration container for service-specific SDK generation.
     * 
     * This class encapsulates all settings needed to generate a separate SDK for 
     * a specific Azure service (e.g., Network, Compute, Storage, etc.).
     * 
     * Each service gets:
     * - Separate package namespace (com.azure.simpleSDK.{service}.*)
     * - Dedicated client class (e.g., AzureNetworkClient, AzureComputeClient)
     * - Isolated models directory structure  
     * - Service-specific API version extraction
     * 
     * This enables the multi-service architecture where applications can depend
     * only on the Azure services they need, rather than a monolithic SDK.
     * 
     * Example:
     * ```java
     * new ServiceSpec(
     *     "Network",                                           // Display name
     *     "azure-rest-api-specs/.../network/.../2024-07-01/", // OpenAPI specs path
     *     "sdk/.../network/models",                            // Models output directory  
     *     "sdk/.../network/client",                            // Client output directory
     *     "com.azure.simpleSDK.network.models",               // Models package
     *     "com.azure.simpleSDK.network.client",               // Client package
     *     "AzureNetworkClient"                                 // Client class name
     * )
     * ```
     */
    private static class ServiceSpec {
        /** Human-readable service name for logging (e.g., "Network", "Compute") */
        final String displayName;
        /** Path to OpenAPI specification files for this service */
        final String specsPath;
        /** Output directory for generated model classes */
        final String modelsOutputDir;
        /** Output directory for generated client class */
        final String clientOutputDir;
        /** Java package name for model classes */
        final String modelsPackage;
        /** Java package name for client class */
        final String clientPackage;
        /** Name of generated client class */
        final String clientClassName;
        /** API version extracted from specs path */
        final String apiVersion;
        
        ServiceSpec(String displayName, String specsPath, String modelsOutputDir, String clientOutputDir, 
                   String modelsPackage, String clientPackage, String clientClassName) {
            this.displayName = displayName;
            this.specsPath = specsPath;
            this.modelsOutputDir = modelsOutputDir;
            this.clientOutputDir = clientOutputDir;
            this.modelsPackage = modelsPackage;
            this.clientPackage = clientPackage;
            this.clientClassName = clientClassName;
            this.apiVersion = extractApiVersionFromPath(specsPath);
        }
        
        private String extractApiVersionFromPath(String specsPath) {
            // Extract API version from path like "azure-rest-api-specs/specification/.../stable/2024-11-01/"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/(\\d{4}-\\d{2}-\\d{2})/");
            java.util.regex.Matcher matcher = pattern.matcher(specsPath);
            return matcher.find() ? matcher.group(1) : "2024-07-01"; // Default fallback
        }
    }
    
    /**
     * Processes definition entries and generates Java record files.
     * Returns the count of successfully generated files.
     */
    private static int processDefinitionEntries(JavaDefinitionGenerator generator, 
                                               Map<DefinitionKey, JsonNode> definitions, 
                                               String modelsOutputDir) {
        int generatedCount = 0;
        for (Map.Entry<DefinitionKey, JsonNode> entry : definitions.entrySet()) {
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
        return generatedCount;
    }

    /**
     * Processes inline enums and generates separate Java enum files.
     * Returns the count of successfully generated files.
     */
    private static int processInlineEnums(JavaDefinitionGenerator generator, String modelsOutputDir) {
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
        return inlineEnumCount;
    }

    /**
     * Common code generation logic used by all generation methods.
     * This eliminates the duplication between generateSDK, generateCombinedSDK, and generateServiceSDK.
     */
    private static void generateModelsAndClient(CodeGenerationContext context) {
        System.out.println("Found " + context.operations.size() + " operations");
        System.out.println("Found " + context.definitions.size() + " definitions");
        
        // Report duplicate definitions
        System.out.println("Duplicate definitions" + (context.displayName != null ? " within " + context.displayName.toLowerCase() : "") + ":");
        if (context.duplicateNames.isEmpty()) {
            System.out.println("No duplicate definition names found.");
        } else {
            for (String duplicateName : context.duplicateNames) {
                System.out.println("Definition '" + duplicateName + "' appears in:");
                context.definitions.entrySet().stream()
                        .filter(entry -> entry.getKey().definitionKey().equals(duplicateName))
                        .forEach(entry -> System.out.println("  - " + entry.getKey().filename()));
            }
        }
        
        // Generate models
        System.out.println("Generating Java records to: " + context.modelsOutputDir);
        JavaDefinitionGenerator generator = context.modelsPackage != null ?
            new JavaDefinitionGenerator(context.duplicateNames, context.definitions, context.modelsPackage) :
            new JavaDefinitionGenerator(context.duplicateNames, context.definitions);
        
        int generatedCount = processDefinitionEntries(generator, context.definitions, context.modelsOutputDir);
        int inlineEnumCount = processInlineEnums(generator, context.modelsOutputDir);
        
        System.out.println("Generated " + generatedCount + " Java record files");
        if (inlineEnumCount > 0) {
            System.out.println("Generated " + inlineEnumCount + " inline enum files");
        }
        
        // Generate client
        String clientInfo = context.displayName != null ? context.displayName + " client" : "Azure SDK client";
        System.out.println("Generating " + clientInfo + ":");
        
        try {
            OperationGenerator operationGenerator;
            if (context.clientPackage != null && context.clientClassName != null && context.apiVersion != null) {
                operationGenerator = new OperationGenerator(context.operations, context.duplicateNames, 
                    context.definitions, context.modelsPackage, context.clientPackage, 
                    context.clientClassName, context.apiVersion);
            } else {
                operationGenerator = new OperationGenerator(context.operations, context.duplicateNames, context.definitions);
            }
            
            operationGenerator.generateAzureClient(context.clientOutputDir);
            System.out.println("Generated " + (context.clientClassName != null ? context.clientClassName : "AzureSimpleSDKClient") 
                             + ".java in " + context.clientOutputDir);
        } catch (IOException e) {
            System.err.println("Error generating " + clientInfo + ": " + e.getMessage());
        }
    }

    private static void generateServiceSDK(ServiceSpec serviceSpec) {
        SpecLoader loader = new SpecLoader(serviceSpec.specsPath);
        
        try {
            SpecResult result = loader.loadSpecs();
            
            CodeGenerationContext context = new CodeGenerationContext(
                result.operations(),
                result.definitions(),
                serviceSpec.modelsOutputDir,
                serviceSpec.clientOutputDir,
                serviceSpec.modelsPackage,
                serviceSpec.clientPackage,
                serviceSpec.clientClassName,
                serviceSpec.apiVersion,
                serviceSpec.displayName
            );
            
            generateModelsAndClient(context);
        } catch (IOException e) {
            System.err.println("Error loading " + serviceSpec.displayName + " specifications: " + e.getMessage());
        }
    }
    
    public static void generateSDK(String specsPath, String modelsOutputDir, String clientOutputDir) {
        SpecLoader loader = new SpecLoader(specsPath);
        
        try {
            SpecResult result = loader.loadSpecs();
            
            // Print detailed operation and definition listings (kept for backward compatibility)
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
            
            System.out.println();
            
            CodeGenerationContext context = new CodeGenerationContext(
                result.operations(),
                result.definitions(),
                modelsOutputDir,
                clientOutputDir,
                null, // modelsPackage
                null, // clientPackage
                null, // clientClassName
                null, // apiVersion
                null  // displayName
            );
            
            generateModelsAndClient(context);
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
        
        CodeGenerationContext context = new CodeGenerationContext(
            combinedOperations,
            combinedDefinitions,
            modelsOutputDir,
            clientOutputDir,
            null, // modelsPackage
            null, // clientPackage
            null, // clientClassName
            null, // apiVersion
            null  // displayName
        );
        
        generateModelsAndClient(context);
    }
    
    /**
     * Collects external file references from an OpenAPI specification for phase 2 loading.
     * 
     * Scans the entire JSON tree for $ref properties that point to external files:
     * - "./file.json#/definitions/Type" (same directory)
     * - "../path/file.json#/definitions/Type" (parent/child directories)
     * 
     * @param rootNode The root JSON node to scan for external references
     * @param currentFileDir The directory of the current file (for resolving relative paths)
     * @param externalRefsToLoad Set to populate with absolute paths of external files to load
     */
    private void collectExternalReferences(JsonNode rootNode, Path currentFileDir, Set<String> externalRefsToLoad) {
        collectExternalReferencesRecursive(rootNode, currentFileDir, externalRefsToLoad);
    }
    
    /**
     * Recursively scans JSON nodes for external file references.
     * 
     * Traverses all JSON objects and arrays looking for $ref properties
     * that contain relative file paths (starting with ./ or ../).
     * 
     * @param node Current JSON node being examined
     * @param currentFileDir Directory of the source file (for path resolution)
     * @param externalRefsToLoad Set to collect external file paths
     */
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
            
            // Convert absolute path to relative path from azure-rest-api-specs/
            String relativePath = getRelativePathFromSpecsRoot(path);
            String filename = path.getFileName().toString();
            
            // Skip if already loaded by full path (avoid duplicates)
            // Note: We use full path instead of just filename to allow different services 
            // to have files with same name but different content
            if (loadedFiles.contains(filePath)) {
                return;
            }
            
            String content = Files.readString(path);
            JsonNode rootNode = mapper.readTree(content);
            
            // Extract definitions from external file using relative path
            JsonNode definitionsNode = rootNode.get("definitions");
            if (definitionsNode != null && definitionsNode.isObject()) {
                extractDefinitions(definitionsNode, relativePath, content, definitions);
                loadedFiles.add(filePath);  // Use full path instead of just filename
                System.out.println("Loaded external file: " + filename + " (path: " + relativePath + ")");
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
