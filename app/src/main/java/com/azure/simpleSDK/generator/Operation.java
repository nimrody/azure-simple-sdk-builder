package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Immutable representation of a REST API operation from OpenAPI specifications.
 * 
 * This record captures all essential information about an API endpoint needed for:
 * - Java method generation in SDK client classes
 * - Parameter extraction and type resolution  
 * - Response type determination
 * - Javadoc generation with operation metadata
 * 
 * Used by OperationGenerator to create type-safe Java client methods that wrap
 * Azure REST API calls with proper error handling and response parsing.
 * 
 * Examples:
 * - operationId: "VirtualNetworkGateways_List"
 * - apiPath: "/subscriptions/{subscriptionId}/providers/Microsoft.Network/virtualNetworkGateways"
 * - httpMethod: "GET"
 * - operationSpec: Complete OpenAPI operation definition (parameters, responses, etc.)
 * - responseSchemas: {"200": "VirtualNetworkGatewayListResult", "default": "ErrorResponse"}
 * 
 * The responseSchemas map allows the generator to determine the correct return type
 * for each possible HTTP response code, enabling type-safe client method signatures.
 * 
 * @param operationId Unique identifier from OpenAPI (used to generate method names)
 * @param apiPath URL template with parameter placeholders (e.g., "/subscriptions/{subscriptionId}/...")
 * @param httpMethod HTTP verb (GET, POST, PUT, DELETE, PATCH)
 * @param operationSpec Complete OpenAPI operation definition containing parameters, responses, etc.
 * @param responseSchemas Map of HTTP status codes to response schema names for type resolution
 * @param documentRoot Root JsonNode of the OpenAPI document containing this operation
 * @param sourceFile Relative path of the source specification file (from azure-rest-api-specs/)
 * @param apiVersion API version resolved from the source specification path
 */
public record Operation(
    String operationId,
    String apiPath,
    String httpMethod,
    JsonNode operationSpec,
    Map<String, String> responseSchemas,
    JsonNode documentRoot,
    String sourceFile,
    String apiVersion
) {
}
