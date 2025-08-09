package com.azure.simpleSDK.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

public record SpecResult(
    Map<String, Operation> operations,
    Map<DefinitionKey, JsonNode> definitions
) {
}