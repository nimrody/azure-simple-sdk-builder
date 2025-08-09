package com.azure.simpleSDK.generator.discovery;

import java.nio.file.Path;

public class SpecificationFile {
    private final Path path;
    private final ApiVersion version;
    
    public SpecificationFile(Path path, ApiVersion version) {
        this.path = path;
        this.version = version;
    }
    
    public Path getPath() {
        return path;
    }
    
    public ApiVersion getVersion() {
        return version;
    }
    
    @Override
    public String toString() {
        return "SpecificationFile{" +
                "path=" + path +
                ", version=" + version +
                '}';
    }
}