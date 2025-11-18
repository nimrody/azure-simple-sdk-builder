package com.azure.simpleSDK.http.recording;

import com.azure.simpleSDK.http.HttpCallResult;
import com.azure.simpleSDK.http.exceptions.AzureException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records HTTP requests/responses to disk and can later replay them without performing network calls.
 */
public class HttpInteractionRecorder {
    public enum Mode {
        LIVE,
        RECORD,
        PLAYBACK
    }

    private final Mode mode;
    private final Path recordingsDirectory;
    private final ObjectMapper objectMapper;
    private final Map<String, Deque<RecordedExchange>> playbackIndex = new HashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(1);

    public HttpInteractionRecorder(Mode mode, Path recordingsDirectory) throws AzureException {
        this.mode = mode == null ? Mode.LIVE : mode;

        if (this.mode != Mode.LIVE && recordingsDirectory == null) {
            throw new AzureException("Recording directory is required for recorder mode: " + this.mode);
        }

        this.recordingsDirectory = recordingsDirectory;
        this.objectMapper = new ObjectMapper();

        if (this.mode == Mode.RECORD) {
            initializeRecordingDirectory();
        } else if (this.mode == Mode.PLAYBACK) {
            loadPlaybackData();
        }
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isRecording() {
        return mode == Mode.RECORD;
    }

    public boolean isPlayback() {
        return mode == Mode.PLAYBACK;
    }

    public void record(HttpRequest request, String requestBody, HttpCallResult result) throws AzureException {
        if (!isRecording()) {
            return;
        }

        try {
            RecordedExchange exchange = new RecordedExchange();
            exchange.signature = buildSignature(request.method(), request.uri().toString(), requestBody);

            RecordedRequest recordedRequest = new RecordedRequest();
            recordedRequest.method = request.method();
            recordedRequest.url = request.uri().toString();
            recordedRequest.headers = extractRequestHeaders(request);
            recordedRequest.body = requestBody;
            recordedRequest.recordedAt = Instant.now().toString();
            exchange.request = recordedRequest;

            RecordedResponse recordedResponse = new RecordedResponse();
            recordedResponse.statusCode = result.statusCode();
            recordedResponse.headers = new HashMap<>(result.headers());
            recordedResponse.body = result.body();
            recordedResponse.recordedAt = Instant.now().toString();
            exchange.response = recordedResponse;

            String fileName = String.format("%05d_%s.json", sequence.getAndIncrement(), exchange.signature);
            Path targetFile = recordingsDirectory.resolve(fileName);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(targetFile.toFile(), exchange);
        } catch (IOException e) {
            throw new AzureException("Failed to record HTTP exchange", e);
        }
    }

    public HttpCallResult playback(HttpRequest request, String requestBody) throws AzureException {
        if (!isPlayback()) {
            throw new AzureException("Recorder is not in playback mode");
        }

        String signature = buildSignature(request.method(), request.uri().toString(), requestBody);
        Deque<RecordedExchange> queue = playbackIndex.get(signature);

        if (queue == null || queue.isEmpty()) {
            throw new AzureException("No recorded response found for request: " + request.method() + " " + request.uri());
        }

        RecordedExchange exchange = queue.pollFirst();
        return new HttpCallResult(
            exchange.response.statusCode,
            Collections.unmodifiableMap(new HashMap<>(exchange.response.headers)),
            exchange.response.body
        );
    }

    private void initializeRecordingDirectory() throws AzureException {
        try {
            Files.createDirectories(recordingsDirectory);
            int startIndex = 1;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(recordingsDirectory, "*.json")) {
                for (Path ignored : stream) {
                    startIndex++;
                }
            }
            sequence.set(startIndex);
        } catch (IOException e) {
            throw new AzureException("Failed to initialize recordings directory: " + recordingsDirectory, e);
        }
    }

    private void loadPlaybackData() throws AzureException {
        try {
            if (!Files.isDirectory(recordingsDirectory)) {
                throw new NoSuchFileException(recordingsDirectory.toString());
            }

            List<Path> files = new java.util.ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(recordingsDirectory, "*.json")) {
                for (Path file : stream) {
                    files.add(file);
                }
            }
            files.sort(Comparator.comparing(Path::getFileName));

            for (Path file : files) {
                RecordedExchange exchange = objectMapper.readValue(file.toFile(), RecordedExchange.class);
                if (exchange.signature == null && exchange.request != null) {
                    exchange.signature = buildSignature(exchange.request.method, exchange.request.url, exchange.request.body);
                }
                if (exchange.signature == null) {
                    continue;
                }
                playbackIndex.computeIfAbsent(exchange.signature, key -> new ArrayDeque<>()).add(exchange);
            }
        } catch (NoSuchFileException e) {
            throw new AzureException("Playback directory does not exist: " + recordingsDirectory, e);
        } catch (IOException e) {
            throw new AzureException("Failed to load recorded HTTP exchanges from: " + recordingsDirectory, e);
        }
    }

    private Map<String, String> extractRequestHeaders(HttpRequest request) {
        Map<String, String> headers = new HashMap<>();
        request.headers().map().forEach((name, values) -> {
            if (!values.isEmpty() && !isSensitiveHeader(name)) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }

    private boolean isSensitiveHeader(String headerName) {
        return "authorization".equalsIgnoreCase(headerName);
    }

    private String buildSignature(String method, String url, String body) throws AzureException {
        String normalizedUrl = normalizeUrl(url);
        String payload = method + "\n" + normalizedUrl + "\n" + (body == null ? "" : body);
        return sha256(payload);
    }

    private String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null || query.isEmpty()) {
                return uri.toString();
            }

            List<String> parts = new ArrayList<>();
            for (String part : query.split("&")) {
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
            parts.sort(String::compareTo);

            String sortedQuery = String.join("&", parts);
            URI normalized = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), sortedQuery, uri.getFragment());
            return normalized.toString();
        } catch (Exception e) {
            return url;
        }
    }

    private String sha256(String payload) throws AzureException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AzureException("Unable to initialize SHA-256 for request recording", e);
        }
    }

    private static class RecordedExchange {
        public String signature;
        public RecordedRequest request;
        public RecordedResponse response;
    }

    private static class RecordedRequest {
        public String method;
        public String url;
        public Map<String, String> headers;
        public String body;
        public String recordedAt;
    }

    private static class RecordedResponse {
        public int statusCode;
        public Map<String, String> headers;
        public String body;
        public String recordedAt;
    }
}
