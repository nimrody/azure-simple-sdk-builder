package com.azure.simpleSDK.generator.parser;

public record Parameter(
    String name,
    String in,
    boolean required,
    String type,
    String description
) {}