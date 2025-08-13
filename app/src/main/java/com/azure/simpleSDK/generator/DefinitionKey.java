package com.azure.simpleSDK.generator;

public record DefinitionKey(
    String filename,
    String definitionKey,
    int lineNumber
) {
}