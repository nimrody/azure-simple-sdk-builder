package com.azure.simpleSDK.http;

/**
 * Mock item model for testing pagination functionality.
 */
public record TestItem(
    String id,
    String name,
    String value
) {
}