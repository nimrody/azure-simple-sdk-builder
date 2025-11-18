package com.azure.simpleSDK.http;

import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple container describing an HTTP response without tying callers to {@link HttpResponse}.
 */
public record HttpCallResult(int statusCode, Map<String, String> headers, String body) {

    public static HttpCallResult fromHttpResponse(HttpResponse<String> response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return new HttpCallResult(response.statusCode(), Collections.unmodifiableMap(headers), response.body());
    }
}
