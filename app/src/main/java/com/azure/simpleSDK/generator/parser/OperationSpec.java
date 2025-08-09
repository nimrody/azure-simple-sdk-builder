package com.azure.simpleSDK.generator.parser;

import java.util.List;
import java.util.Map;

public record OperationSpec(
    String operationId,
    String httpMethod,
    String path,
    List<Parameter> parameters,
    Map<String, Response> responses,
    String description
) {}