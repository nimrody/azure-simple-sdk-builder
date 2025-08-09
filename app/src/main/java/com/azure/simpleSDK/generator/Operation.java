package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record Operation(
    String operationId,
    String apiPath,
    String httpMethod,
    JsonNode operationSpec,
    Map<String, String> responseSchemas
) {
}