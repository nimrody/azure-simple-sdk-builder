package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Immutable container for the complete results of OpenAPI specification loading.
 * 
 * This record represents the outcome of the two-phase loading process performed by SpecLoader:
 * 1. Phase 1: Load all JSON files and extract operations + definitions + external references
 * 2. Phase 2: Load external referenced files recursively to resolve dependencies
 * 
 * The result contains everything needed for code generation:
 * - All REST API operations with their metadata
 * - All model definitions with source traceability
 * - Cross-file reference resolution completed
 * - AllOf inheritance relationships preserved
 * 
 * Used by generators to:
 * - Create Java client classes (operations -> methods)
 * - Generate model classes (definitions -> records/enums)  
 * - Handle duplicate definition names across services
 * - Maintain source file traceability in generated code
 * 
 * Typical usage:
 * ```java
 * SpecLoader loader = new SpecLoader("azure-rest-api-specs/specification/network/...");
 * SpecResult result = loader.loadSpecs();
 * 
 * // result.operations() contains all API endpoints
 * // result.definitions() contains all model schemas with source locations
 * ```
 * 
 * @param operations All loaded API operations indexed by operationId for fast lookup
 * @param definitions All loaded model definitions with source traceability via DefinitionKey
 */
public record SpecResult(
    Map<String, Operation> operations,
    Map<DefinitionKey, JsonNode> definitions
) {
}