package com.azure.simpleSDK.generator.discovery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SpecificationFinderTest {
    
    @TempDir
    Path tempDir;
    
    private SpecificationFinder finder;
    
    @BeforeEach
    void setUp() {
        finder = new SpecificationFinder(tempDir);
    }
    
    @Test
    void testFindOperationSpecNotFound() {
        SpecificationFile result = finder.findOperationSpec("NonExistentOperation");
        assertThat(result).isNull();
    }
    
    @Test
    void testApiVersionParsing() {
        ApiVersion version = new ApiVersion("2024-07-01", 
                                          java.time.LocalDate.of(2024, 7, 1), 
                                          true);
        
        assertThat(version.getVersionString()).isEqualTo("2024-07-01");
        assertThat(version.isStable()).isTrue();
        assertThat(version.getDate().getYear()).isEqualTo(2024);
    }
}