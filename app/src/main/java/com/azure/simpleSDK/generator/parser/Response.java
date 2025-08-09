package com.azure.simpleSDK.generator.parser;

public record Response(
    String statusCode,
    String description,
    String schemaType
) {}