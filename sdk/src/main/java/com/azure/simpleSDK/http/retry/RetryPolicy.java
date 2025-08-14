package com.azure.simpleSDK.http.retry;

import java.time.Duration;
import java.util.Set;

public class RetryPolicy {
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Set<Integer> retryableStatusCodes;
    private final boolean retryOnTimeout;
    private final boolean retryOnNetworkError;

    public static final RetryPolicy DEFAULT = new Builder()
        .maxAttempts(5)
        .baseDelay(Duration.ofMillis(100))
        .maxDelay(Duration.ofMillis(1600))
        .retryableStatusCodes(Set.of(429, 502, 503, 504))
        .retryOnTimeout(true)
        .retryOnNetworkError(true)
        .build();

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.baseDelay = builder.baseDelay;
        this.maxDelay = builder.maxDelay;
        this.retryableStatusCodes = Set.copyOf(builder.retryableStatusCodes);
        this.retryOnTimeout = builder.retryOnTimeout;
        this.retryOnNetworkError = builder.retryOnNetworkError;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getBaseDelay() {
        return baseDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public boolean shouldRetry(int statusCode) {
        return retryableStatusCodes.contains(statusCode);
    }

    public boolean shouldRetryOnTimeout() {
        return retryOnTimeout;
    }

    public boolean shouldRetryOnNetworkError() {
        return retryOnNetworkError;
    }

    public static class Builder {
        private int maxAttempts = 3;
        private Duration baseDelay = Duration.ofMillis(100);
        private Duration maxDelay = Duration.ofSeconds(30);
        private Set<Integer> retryableStatusCodes = Set.of(429, 502, 503, 504);
        private boolean retryOnTimeout = true;
        private boolean retryOnNetworkError = true;

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder baseDelay(Duration baseDelay) {
            if (baseDelay.isNegative() || baseDelay.isZero()) {
                throw new IllegalArgumentException("baseDelay must be positive");
            }
            this.baseDelay = baseDelay;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            if (maxDelay.isNegative() || maxDelay.isZero()) {
                throw new IllegalArgumentException("maxDelay must be positive");
            }
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder retryableStatusCodes(Set<Integer> statusCodes) {
            this.retryableStatusCodes = statusCodes;
            return this;
        }

        public Builder retryOnTimeout(boolean retryOnTimeout) {
            this.retryOnTimeout = retryOnTimeout;
            return this;
        }

        public Builder retryOnNetworkError(boolean retryOnNetworkError) {
            this.retryOnNetworkError = retryOnNetworkError;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }
    }
}