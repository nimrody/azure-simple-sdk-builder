package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;

public record Operation(
    String operationId,
    String apiPath,
    String httpMethod,
    JsonNode operationSpec
) {
}