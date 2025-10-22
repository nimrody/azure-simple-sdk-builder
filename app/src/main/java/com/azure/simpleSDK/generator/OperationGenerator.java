package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates Azure SDK client classes from OpenAPI operations.
 * 
 * This generator creates type-safe Java client classes that wrap Azure REST APIs:
 * 
 * Generated Output:
 * - Java client class with methods for each GET operation
 * - Type-safe method signatures with proper parameter extraction
 * - Comprehensive Javadoc with operation metadata
 * - Response types using generated model classes
 * - Proper exception handling with AzureException
 * 
 * Key Features:
 * - Operation ID to Java method name conversion
 * - Parameter extraction from OpenAPI specs (path, query, body)
 * - Response schema resolution with duplicate handling
 * - Service-specific package and class name support
 * - API version configuration
 * - HTML escaping for Javadoc safety
 */
public class OperationGenerator {
    /** All loaded OpenAPI operations indexed by operation ID */
    private final Map<String, Operation> operations;
    /** Set of definition names that appear in multiple files */
    private final Set<String> duplicateDefinitions;
    /** All loaded definitions for response type resolution */
    private final Map<DefinitionKey, JsonNode> definitions;
    /** Target package for model classes (e.g., "com.azure.simpleSDK.network.models") */
    private final String modelsPackage;
    /** Target package for client classes (e.g., "com.azure.simpleSDK.network.client") */
    private final String clientPackage;
    /** Name of the generated client class (e.g., "AzureNetworkClient") */
    private final String clientClassName;
    /** Azure API version to use in requests */
    private final String apiVersion;

    /**
     * Creates an OperationGenerator with default package names and settings.
     * 
     * @param operations All loaded operations from OpenAPI specifications
     * @param duplicateDefinitions Set of definition names that appear in multiple files
     * @param definitions All loaded definitions for response type resolution
     */
    public OperationGenerator(Map<String, Operation> operations, Set<String> duplicateDefinitions, Map<DefinitionKey, JsonNode> definitions) {
        this.operations = operations;
        this.duplicateDefinitions = duplicateDefinitions;
        this.definitions = definitions;
        this.modelsPackage = "com.azure.simpleSDK.models"; // Default for backwards compatibility
        this.clientPackage = "com.azure.simpleSDK.client";
        this.clientClassName = "AzureSimpleSDKClient";
        this.apiVersion = "2024-07-01"; // Default for backwards compatibility
    }
    
    /**
     * Creates an OperationGenerator with custom package and class names for service-specific generation.
     * 
     * @param operations All loaded operations from OpenAPI specifications
     * @param duplicateDefinitions Set of definition names that appear in multiple files
     * @param definitions All loaded definitions for response type resolution
     * @param modelsPackage Target package for model classes (e.g., "com.azure.simpleSDK.network.models")
     * @param clientPackage Target package for client class (e.g., "com.azure.simpleSDK.network.client")
     * @param clientClassName Name of generated client class (e.g., "AzureNetworkClient")
     */
    public OperationGenerator(Map<String, Operation> operations, Set<String> duplicateDefinitions, 
                            Map<DefinitionKey, JsonNode> definitions, String modelsPackage, 
                            String clientPackage, String clientClassName) {
        this.operations = operations;
        this.duplicateDefinitions = duplicateDefinitions;
        this.definitions = definitions;
        this.modelsPackage = modelsPackage != null ? modelsPackage : "com.azure.simpleSDK.models";
        this.clientPackage = clientPackage != null ? clientPackage : "com.azure.simpleSDK.client";
        this.clientClassName = clientClassName != null ? clientClassName : "AzureSimpleSDKClient";
        this.apiVersion = "2024-07-01"; // Default for backwards compatibility
    }
    
    /**
     * Creates an OperationGenerator with full customization including API version.
     * 
     * @param operations All loaded operations from OpenAPI specifications
     * @param duplicateDefinitions Set of definition names that appear in multiple files
     * @param definitions All loaded definitions for response type resolution
     * @param modelsPackage Target package for model classes (e.g., "com.azure.simpleSDK.network.models")
     * @param clientPackage Target package for client class (e.g., "com.azure.simpleSDK.network.client")
     * @param clientClassName Name of generated client class (e.g., "AzureNetworkClient")
     * @param apiVersion Azure API version for requests (e.g., "2024-07-01")
     */
    public OperationGenerator(Map<String, Operation> operations, Set<String> duplicateDefinitions, 
                            Map<DefinitionKey, JsonNode> definitions, String modelsPackage, 
                            String clientPackage, String clientClassName, String apiVersion) {
        this.operations = operations;
        this.duplicateDefinitions = duplicateDefinitions;
        this.definitions = definitions;
        this.modelsPackage = modelsPackage != null ? modelsPackage : "com.azure.simpleSDK.models";
        this.clientPackage = clientPackage != null ? clientPackage : "com.azure.simpleSDK.client";
        this.clientClassName = clientClassName != null ? clientClassName : "AzureSimpleSDKClient";
        this.apiVersion = apiVersion != null ? apiVersion : "2024-07-01";
    }

    /**
     * Generates a complete Azure SDK client class with methods for all GET operations.
     * 
     * Input: Output directory path for the generated client
     * Output: Java client class file with complete implementation
     * 
     * Generated Client Features:
     * - Constructor accepting AzureCredentials and optional strict mode
     * - Methods for all GET operations with proper parameter extraction
     * - Type-safe return types using generated model classes  
     * - Comprehensive Javadoc with operation metadata
     * - Proper exception handling and HTTP client integration
     * 
     * Method Generation Process:
     * 1. Filter operations to GET requests only
     * 2. Convert operation IDs to Java method names (e.g., "VirtualMachines_List" -> "listVirtualMachines")
     * 3. Extract parameters from OpenAPI path and query parameters
     * 4. Resolve response types with duplicate handling
     * 5. Generate complete Javadoc with operation details
     * 
     * @param outputDir The directory path where the client class file will be created
     * @throws IOException if file writing fails
     */
    public void generateAzureClient(String outputDir) throws IOException {
        List<Operation> getOperations = operations.values().stream()
                .filter(op -> "GET".equals(op.httpMethod()))
                .sorted(Comparator.comparing(Operation::operationId))
                .toList();

        String className = this.clientClassName;
        StringBuilder classContent = new StringBuilder();
        
        // Package and imports
        classContent.append("package ").append(clientPackage).append(";\n\n");
        classContent.append("import com.azure.simpleSDK.http.*;\n");
        classContent.append("import com.azure.simpleSDK.http.auth.AzureCredentials;\n");
        classContent.append("import com.azure.simpleSDK.http.exceptions.AzureException;\n");
        classContent.append("import ").append(modelsPackage).append(".*;\n");
        classContent.append("import java.util.Map;\n");
        classContent.append("import java.util.HashMap;\n\n");

        // Class definition
        classContent.append("public class ").append(className).append(" {\n");
        classContent.append("    private final AzureHttpClient httpClient;\n\n");

        // Constructors
        classContent.append("    public ").append(className).append("(AzureCredentials credentials) {\n");
        classContent.append("        this.httpClient = new AzureHttpClient(credentials);\n");
        classContent.append("    }\n\n");
        
        classContent.append("    public ").append(className).append("(AzureCredentials credentials, boolean strictMode) {\n");
        classContent.append("        this.httpClient = new AzureHttpClient(credentials, strictMode);\n");
        classContent.append("    }\n\n");

        // Generate methods for GET operations
        for (Operation operation : getOperations) {
            generateOperationMethod(classContent, operation);
        }

        classContent.append("}\n");

        // Write to file
        Path outputPath = Paths.get(outputDir);
        Files.createDirectories(outputPath);
        Path filePath = outputPath.resolve(className + ".java");
        Files.writeString(filePath, classContent.toString());
    }

    private void generateOperationMethod(StringBuilder classContent, Operation operation) {
        String methodName = convertOperationIdToMethodName(operation.operationId());
        String returnType = getReturnType(operation);
        List<Parameter> pathParams = extractPathParameters(operation.apiPath(), operation.operationSpec());
        List<Parameter> queryParams = extractQueryParameters(operation.operationSpec());
        
        // Extract description from operation spec
        String description = extractDescription(operation.operationSpec());
        
        // Generate Javadoc comment
        generateJavadoc(classContent, operation, description, pathParams, queryParams);
        
        // Method signature
        classContent.append("    public AzureResponse<").append(returnType).append("> ").append(methodName).append("(");
        
        // Parameters
        List<Parameter> allParams = new ArrayList<>();
        allParams.addAll(pathParams);
        allParams.addAll(queryParams);
        
        for (int i = 0; i < allParams.size(); i++) {
            Parameter param = allParams.get(i);
            classContent.append(param.javaType).append(" ").append(param.name);
            if (i < allParams.size() - 1) {
                classContent.append(", ");
            }
        }
        
        classContent.append(") throws AzureException {\n");
        
        // Method body
        String urlPath = operation.apiPath();
        for (Parameter param : pathParams) {
            urlPath = urlPath.replace("{" + param.name + "}", "\" + " + param.name + " + \"");
        }
        urlPath = "\"" + urlPath + "\"";
        
        classContent.append("        String url = ").append(urlPath).append(";\n");
        classContent.append("        AzureRequest request = httpClient.get(url)\n");
        classContent.append("                .version(\"").append(apiVersion).append("\")");
        
        // Add query parameters
        for (Parameter param : queryParams) {
            if (!param.name.equals("api-version")) {
                classContent.append("\n                .queryParam(\"").append(param.name).append("\", ").append(param.name).append(")");
            }
        }
        
        classContent.append(";\n");
        classContent.append("        return httpClient.execute(request, ").append(returnType).append(".class);\n");
        classContent.append("    }\n\n");
    }

    /**
     * Converts OpenAPI operation IDs to proper Java method names.
     * 
     * Input: Azure operation ID (e.g., "VirtualNetworkGateways_Get", "VirtualMachines_List")  
     * Output: camelCase Java method name
     * 
     * Conversion Examples:
     * - "VirtualNetworkGateways_Get" -> "getVirtualNetworkGateways"
     * - "VirtualNetworkGateways_ListConnections" -> "listConnectionsVirtualNetworkGateways" 
     * - "VirtualMachines_List" -> "listVirtualMachines"
     * - "PublicIPAddresses_GetCloudServicePublicIPAddress" -> "getCloudServicePublicIPAddressPublicIPAddresses"
     * 
     * Algorithm:
     * 1. Split on underscore to get [Resource, Verb] parts
     * 2. Handle compound verbs (e.g., "ListConnections" -> "listConnections") 
     * 3. Convert first verb word to lowercase, keep rest capitalized
     * 4. Append resource name with first letter capitalized
     * 
     * @param operationId The Azure OpenAPI operation ID
     * @return Java method name in camelCase
     */
    private String convertOperationIdToMethodName(String operationId) {
        // Convert operation ID like "VirtualNetworkGateways_Get" to "getVirtualNetworkGateways"
        // Handle compound verbs like "VirtualNetworkGateways_ListConnections" to "listConnectionsVirtualNetworkGateways"
        String[] parts = operationId.split("_");
        if (parts.length >= 2) {
            String verbPart = parts[1];
            String resource = parts[0];
            
            // Convert PascalCase resource to camelCase
            resource = resource.substring(0, 1).toLowerCase() + resource.substring(1);
            
            // Handle compound verbs by converting PascalCase to camelCase
            String verb = verbPart.substring(0, 1).toLowerCase() + verbPart.substring(1);
            
            return verb + Character.toUpperCase(resource.charAt(0)) + resource.substring(1);
        }
        
        // Fallback: just make first char lowercase
        return operationId.substring(0, 1).toLowerCase() + operationId.substring(1);
    }

    private String getReturnType(Operation operation) {
        // Get the success response type (200)
        String responseSchema = operation.responseSchemas().get("200");
        if (responseSchema != null) {
            // Parse reference like "#/definitions/VirtualNetworkGateway"
            if (responseSchema.startsWith("#/definitions/")) {
                String definitionName = responseSchema.substring("#/definitions/".length());
                return resolveDefinitionClassName(definitionName);
            }
            // Parse external reference like "./networkInterface.json#/definitions/NetworkInterfaceListResult"
            else if (responseSchema.contains("#/definitions/")) {
                int definitionsIndex = responseSchema.indexOf("#/definitions/");
                String definitionName = responseSchema.substring(definitionsIndex + "#/definitions/".length());
                return resolveDefinitionClassName(definitionName);
            }
        }
        
        return "Object";
    }

    private String resolveDefinitionClassName(String definitionName) {
        // Check if this is a duplicate definition that needs prefix
        if (duplicateDefinitions.contains(definitionName)) {
            // Find the latest version of this definition
            DefinitionKey latest = definitions.keySet().stream()
                    .filter(key -> key.definitionKey().equals(definitionName))
                    .max(Comparator.comparing(key -> extractDateFromFilename(key.filename())))
                    .orElse(null);
            
            if (latest != null) {
                return getClassNameForDuplicate(latest.filename(), definitionName);
            }
        }
        
        // Sanitize definition name even for non-duplicates
        return sanitizeJavaClassName(definitionName);
    }

    private String extractDateFromFilename(String filename) {
        // Extract date like "2024-07-01" from filename
        Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        java.util.regex.Matcher matcher = datePattern.matcher(filename);
        return matcher.find() ? matcher.group(1) : "0000-00-00";
    }

    private String getClassNameForDuplicate(String filename, String definitionName) {
        // Get the base filename without path and extension
        String baseName = Paths.get(filename).getFileName().toString();
        if (baseName.endsWith(".json")) {
            baseName = baseName.substring(0, baseName.length() - 5);
        }
        
        // Convert to PascalCase and append to definition name
        // Special handling for already PascalCase names like "ComputeRP"
        String prefix;
        if (baseName.matches("^[A-Z][a-z]*[A-Z][A-Z]*$") || baseName.matches("^[A-Z][a-zA-Z]*$")) {
            // Already in PascalCase format (e.g., "ComputeRP", "NetworkProfile")
            prefix = baseName;
        } else {
            // Convert dashed or underscored names to PascalCase
            prefix = Arrays.stream(baseName.split("[^a-zA-Z0-9]"))
                    .filter(part -> !part.isEmpty())
                    .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase())
                    .collect(Collectors.joining(""));
        }
        
        // Sanitize definition name to be a valid Java class name (same logic as JavaDefinitionGenerator)
        String sanitizedDefinitionName = sanitizeJavaClassName(definitionName);
        
        return prefix + sanitizedDefinitionName;
    }
    
    private String sanitizeJavaClassName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        
        // Replace dots and other invalid characters with empty string to create valid Java class name
        // For "TypeSpec.Http.OkResponse" -> "TypeSpecHttpOkResponse"
        return name.replaceAll("\\.", "").replaceAll("[^a-zA-Z0-9_]", "");
    }

    private List<Parameter> extractPathParameters(String apiPath, JsonNode operationSpec) {
        List<Parameter> params = new ArrayList<>();
        Pattern pathParamPattern = Pattern.compile("\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pathParamPattern.matcher(apiPath);
        
        while (matcher.find()) {
            String paramName = matcher.group(1);
            params.add(new Parameter(paramName, "String", true));
        }
        
        return params;
    }

    private List<Parameter> extractQueryParameters(JsonNode operationSpec) {
        List<Parameter> params = new ArrayList<>();
        JsonNode parametersNode = operationSpec.get("parameters");
        
        if (parametersNode != null && parametersNode.isArray()) {
            for (JsonNode paramNode : parametersNode) {
                JsonNode inNode = paramNode.get("in");
                if (inNode != null && "query".equals(inNode.asText())) {
                    JsonNode nameNode = paramNode.get("name");
                    JsonNode requiredNode = paramNode.get("required");
                    
                    if (nameNode != null && !nameNode.asText().equals("api-version")) {
                        String paramName = nameNode.asText();
                        boolean required = requiredNode != null && requiredNode.asBoolean();
                        params.add(new Parameter(paramName, "String", required));
                    }
                }
            }
        }
        
        return params;
    }

    private static class Parameter {
        final String name;
        final String javaType;
        final boolean required;

        Parameter(String name, String javaType, boolean required) {
            this.name = name;
            this.javaType = javaType;
            this.required = required;
        }
    }
    
    private String extractDescription(JsonNode operationSpec) {
        JsonNode descriptionNode = operationSpec.get("description");
        if (descriptionNode != null && descriptionNode.isTextual()) {
            return descriptionNode.asText();
        }
        return "Azure operation";
    }
    
    private void generateJavadoc(StringBuilder classContent, Operation operation, String description, 
                               List<Parameter> pathParams, List<Parameter> queryParams) {
        classContent.append("    /**\n");
        
        // Main description
        classContent.append("     * ").append(escapeJavadoc(description)).append("\n");
        classContent.append("     *\n");
        
        // OpenAPI operation details
        classContent.append("     * @apiNote Operation ID: ").append(operation.operationId()).append("\n");
        classContent.append("     * @apiNote HTTP Method: ").append(operation.httpMethod()).append("\n");
        classContent.append("     * @apiNote URL: ").append(operation.apiPath()).append("\n");
        
        // Parameters
        List<Parameter> allParams = new ArrayList<>();
        allParams.addAll(pathParams);
        allParams.addAll(queryParams);
        
        for (Parameter param : allParams) {
            String paramDescription = extractParameterDescription(operation.operationSpec(), param.name);
            classContent.append("     * @param ").append(param.name).append(" ").append(escapeJavadoc(paramDescription)).append("\n");
        }
        
        // Return value
        classContent.append("     * @return AzureResponse containing the operation result\n");
        classContent.append("     * @throws AzureException if the request fails\n");
        classContent.append("     */\n");
    }
    
    private String extractParameterDescription(JsonNode operationSpec, String parameterName) {
        JsonNode parametersNode = operationSpec.get("parameters");
        if (parametersNode != null && parametersNode.isArray()) {
            for (JsonNode paramNode : parametersNode) {
                JsonNode nameNode = paramNode.get("name");
                if (nameNode != null && nameNode.asText().equals(parameterName)) {
                    JsonNode descNode = paramNode.get("description");
                    if (descNode != null && descNode.isTextual()) {
                        return descNode.asText();
                    }
                }
            }
        }
        return "Parameter " + parameterName;
    }
    
    private String escapeJavadoc(String text) {
        if (text == null) {
            return "";
        }
        // Escape HTML special characters and handle line breaks
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("\n", "\n     * ");
    }
}