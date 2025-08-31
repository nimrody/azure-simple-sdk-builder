package com.azure.simpleSDK.http.retry;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

        // Add jitter: a random value up to 50% of the exponential delay
        long jitter = (long) (exponentialDelay * 0.5 * random.nextDouble());
        long delayWithJitter = exponentialDelay + jitter;

        // Cap the delay at the maximum
        long finalDelay = Math.min(delayWithJitter, maxDelayMs);

        return Duration.ofMillis(finalDelay);
    }

    private Duration parseRetryAfterHeader(String retryAfterValue) {
        if (retryAfterValue == null || retryAfterValue.isEmpty()) {
            return null;
        }

        // Try parsing as seconds
        try {
            if (retryAfterValue.matches("\\d+")) {
                long seconds = Long.parseLong(retryAfterValue);
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException e) {
            // Not a number, fall through to date parsing
        }

        // Try parsing as an HTTP-date
        try {
            ZonedDateTime retryAfterTime = ZonedDateTime.parse(retryAfterValue, DateTimeFormatter.RFC_1123_DATE_TIME);
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            Duration delay = Duration.between(now, retryAfterTime);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            // Not a valid date format
        }

        return null;
    }
}