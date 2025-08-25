package com.azure.simpleSDK.generator;

/**
 * Immutable key for uniquely identifying OpenAPI definitions with source traceability.
 * 
 * This record serves as a composite key that combines:
 * - Source file location (relative to azure-rest-api-specs/)
 * - Definition name within that file 
 * - Line number for precise source tracking
 * 
 * Used throughout the code generation process to:
 * - Track definition sources for generated code comments
 * - Handle duplicate definition names across files
 * - Enable cross-file reference resolution
 * - Maintain traceability from generated code back to OpenAPI specs
 * 
 * Examples:
 * - filename: "specification/network/stable/2024-07-01/network.json"
 * - definitionKey: "VirtualNetwork" 
 * - lineNumber: 1245
 * 
 * This enables generating source comments like:
 * // Generated from specification/network/stable/2024-07-01/network.json:1245
 * 
 * @param filename Path to the OpenAPI file relative to azure-rest-api-specs/ root
 * @param definitionKey Name of the definition within the file (e.g., "VirtualMachine")  
 * @param lineNumber 1-based line number where the definition appears in the source file
 */
public record DefinitionKey(
    String filename,
    String definitionKey,
    int lineNumber
) {
}