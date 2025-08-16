package com.azure.simpleSDK.http;

import java.util.List;

/**
 * Mock list result model for testing pagination functionality.
 * This follows the same pattern as Azure list result models.
 */
public record TestListResult(
    List<TestItem> value,
    String nextLink
) {
}