package com.azure.simpleSDK.http.retry;

import java.time.Duration;
import java.util.Map;
import java.util.Random;

public class ExponentialBackoffStrategy {
    private final Random random;

    public ExponentialBackoffStrategy() {
        this.random = new Random();
    }

    public Duration calculateDelay(int attemptNumber, RetryPolicy retryPolicy, Map<String, String> responseHeaders) {
        if (responseHeaders != null && responseHeaders.containsKey("Retry-After")) {
            Duration retryAfterDelay = parseRetryAfterHeader(responseHeaders.get("Retry-After"));
            if (retryAfterDelay != null) {
                return retryAfterDelay;
            }
        }

        long baseDelayMs = retryPolicy.getBaseDelay().toMillis();
        long maxDelayMs = retryPolicy.getMaxDelay().toMillis();
        
        long exponentialDelay = baseDelayMs * (1L << (attemptNumber - 1));
        
        long cappedDelay = Math.min(exponentialDelay, maxDelayMs);
        
        long jitteredDelay = cappedDelay + random.nextLong(cappedDelay / 2);
        
        return Duration.ofMillis(Math.min(jitteredDelay, maxDelayMs));
    }

    private Duration parseRetryAfterHeader(String retryAfterValue) {
        try {
            if (retryAfterValue.matches("\\d+")) {
                int seconds = Integer.parseInt(retryAfterValue);
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException e) {
        }
        return null;
    }
}