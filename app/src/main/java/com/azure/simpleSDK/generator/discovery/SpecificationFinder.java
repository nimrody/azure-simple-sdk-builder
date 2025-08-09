package com.azure.simpleSDK.generator.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SpecificationFinder {
    private static final Logger logger = LoggerFactory.getLogger(SpecificationFinder.class);
    private final Path specsRoot;
    private final ObjectMapper objectMapper;
    private final Map<String, List<SpecificationFile>> operationCache = new HashMap<>();
    
    // Pattern to extract date from path: /stable/YYYY-MM-DD/ or /preview/YYYY-MM-DD-preview/
    private static final Pattern DATE_PATTERN = Pattern.compile(".*/(?:stable|preview)/(\\d{4}-\\d{2}-\\d{2})(?:-preview)?/.*");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    public SpecificationFinder(Path specsRoot) {
        this.specsRoot = specsRoot;
        this.objectMapper = new ObjectMapper();
    }
    
    public SpecificationFile findOperationSpec(String operationId) {
        logger.info("Searching for operation: {}", operationId);
        
        if (operationCache.containsKey(operationId)) {
            List<SpecificationFile> candidates = operationCache.get(operationId);
            return selectBestCandidate(candidates);
        }
        
        List<SpecificationFile> candidates = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(specsRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(path -> path.toString().endsWith(".json"))
                 .filter(this::isSpecificationFile)
                 .forEach(path -> {
                     if (containsOperation(path, operationId)) {
                         SpecificationFile specFile = createSpecificationFile(path);
                         if (specFile != null) {
                             candidates.add(specFile);
                         }
                     }
                 });
        } catch (IOException e) {
            logger.error("Error walking specification directory: {}", specsRoot, e);
            return null;
        }
        
        if (candidates.isEmpty()) {
            logger.warn("No specification files found containing operation: {}", operationId);
            return null;
        }
        
        operationCache.put(operationId, candidates);
        return selectBestCandidate(candidates);
    }
    
    private boolean isSpecificationFile(Path path) {
        String pathString = path.toString();
        
        // Skip common non-spec files
        if (pathString.contains("/examples/") ||
            pathString.contains("/test/") ||
            pathString.endsWith("/package.json") ||
            pathString.endsWith("/tsconfig.json") ||
            pathString.contains("readme")) {
            return false;
        }
        
        // Must be under resource-manager or data-plane
        return pathString.contains("/resource-manager/") || pathString.contains("/data-plane/");
    }
    
    private boolean containsOperation(Path path, String operationId) {
        try {
            String content = Files.readString(path);
            // Quick string search first for performance
            if (!content.contains(operationId)) {
                return false;
            }
            
            // Verify it's actually an operationId field
            JsonNode root = objectMapper.readTree(content);
            JsonNode paths = root.get("paths");
            if (paths == null) {
                return false;
            }
            
            for (JsonNode pathNode : paths) {
                for (JsonNode methodNode : pathNode) {
                    JsonNode operationIdNode = methodNode.get("operationId");
                    if (operationIdNode != null && operationId.equals(operationIdNode.asText())) {
                        return true;
                    }
                }
            }
            
            return false;
        } catch (IOException e) {
            logger.debug("Failed to read specification file: {}", path, e);
            return false;
        }
    }
    
    private SpecificationFile createSpecificationFile(Path path) {
        try {
            ApiVersion version = extractApiVersion(path);
            return new SpecificationFile(path, version);
        } catch (Exception e) {
            logger.debug("Failed to create specification file for: {}", path, e);
            return null;
        }
    }
    
    private ApiVersion extractApiVersion(Path path) {
        String pathString = path.toString();
        
        Matcher matcher = DATE_PATTERN.matcher(pathString);
        if (matcher.matches()) {
            String dateString = matcher.group(1);
            try {
                LocalDate date = LocalDate.parse(dateString, DATE_FORMATTER);
                boolean isStable = pathString.contains("/stable/");
                return new ApiVersion(dateString, date, isStable);
            } catch (DateTimeParseException e) {
                logger.warn("Failed to parse date from path: {}", pathString);
            }
        }
        
        // Fallback for paths without clear versioning
        return new ApiVersion("unknown", LocalDate.MIN, false);
    }
    
    private SpecificationFile selectBestCandidate(List<SpecificationFile> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        
        // Sort by preference: stable versions first, then by date (newest first)
        candidates.sort((a, b) -> {
            ApiVersion versionA = a.getVersion();
            ApiVersion versionB = b.getVersion();
            
            // Prefer stable over preview
            if (versionA.isStable() && !versionB.isStable()) {
                return -1;
            } else if (!versionA.isStable() && versionB.isStable()) {
                return 1;
            }
            
            // If both stable or both preview, prefer newer date
            return versionB.getDate().compareTo(versionA.getDate());
        });
        
        SpecificationFile selected = candidates.get(0);
        logger.info("Selected specification: {} (version: {}, stable: {})", 
                   selected.getPath(), 
                   selected.getVersion().getVersionString(),
                   selected.getVersion().isStable());
        
        return selected;
    }
}