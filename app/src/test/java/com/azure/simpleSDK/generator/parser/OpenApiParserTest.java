package com.azure.simpleSDK.generator.parser;

import com.azure.simpleSDK.generator.discovery.ApiVersion;
import com.azure.simpleSDK.generator.discovery.SpecificationFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiParserTest {
    
    @TempDir
    Path tempDir;
    
    private OpenApiParser parser;
    
    @BeforeEach
    void setUp() {
        parser = new OpenApiParser();
    }
    
    @Test
    void testCacheManagement() {
        assertThat(parser.getCacheSize()).isEqualTo(0);
        parser.clearCache();
        assertThat(parser.getCacheSize()).isEqualTo(0);
    }
    
    @Test
    void testParseOperationWithExternalReferences() throws Exception {
        // Create a main spec file
        String mainSpec = """
            {
              "swagger": "2.0",
              "info": {
                "title": "Test API",
                "version": "1.0.0"
              },
              "paths": {
                "/test": {
                  "get": {
                    "operationId": "Test_Get",
                    "description": "Test operation",
                    "parameters": [
                      {
                        "$ref": "./common.json#/parameters/TestParameter"
                      }
                    ],
                    "responses": {
                      "200": {
                        "description": "Success",
                        "schema": {
                          "$ref": "#/definitions/TestResponse"
                        }
                      },
                      "default": {
                        "$ref": "./common.json#/responses/ErrorResponse"
                      }
                    }
                  }
                }
              },
              "definitions": {
                "TestResponse": {
                  "type": "object",
                  "properties": {
                    "value": {
                      "type": "string"
                    }
                  }
                }
              }
            }
            """;
        
        // Create an external reference file
        String commonSpec = """
            {
              "parameters": {
                "TestParameter": {
                  "name": "testParam",
                  "in": "query",
                  "type": "string",
                  "required": false,
                  "description": "Test parameter from external file"
                }
              },
              "responses": {
                "ErrorResponse": {
                  "description": "Error response from external file",
                  "schema": {
                    "type": "object",
                    "properties": {
                      "error": {
                        "type": "string"
                      }
                    }
                  }
                }
              }
            }
            """;
        
        Path mainSpecPath = tempDir.resolve("main.json");
        Path commonSpecPath = tempDir.resolve("common.json");
        
        Files.writeString(mainSpecPath, mainSpec);
        Files.writeString(commonSpecPath, commonSpec);
        
        SpecificationFile specFile = new SpecificationFile(
            mainSpecPath, 
            new ApiVersion("1.0.0", LocalDate.now(), true)
        );
        
        // Parse the operation
        OperationSpec result = parser.parseOperation(specFile, "Test_Get");
        
        // Verify the operation was parsed
        assertThat(result).isNotNull();
        assertThat(result.operationId()).isEqualTo("Test_Get");
        assertThat(result.description()).isEqualTo("Test operation");
        assertThat(result.httpMethod()).isEqualTo("GET");
        assertThat(result.path()).isEqualTo("/test");
        
        // Verify parameters were resolved from external file
        assertThat(result.parameters()).hasSize(1);
        Parameter param = result.parameters().get(0);
        assertThat(param.name()).isEqualTo("testParam");
        assertThat(param.in()).isEqualTo("query");
        assertThat(param.type()).isEqualTo("string");
        assertThat(param.description()).isEqualTo("Test parameter from external file");
        assertThat(param.required()).isFalse();
        
        // Verify responses were resolved
        assertThat(result.responses()).hasSize(2);
        assertThat(result.responses()).containsKey("200");
        assertThat(result.responses()).containsKey("default");
        
        Response errorResponse = result.responses().get("default");
        assertThat(errorResponse.description()).isEqualTo("Error response from external file");
        
        // Verify cache was populated
        assertThat(parser.getCacheSize()).isEqualTo(1);
        
        // Parse again to test cache usage
        OperationSpec result2 = parser.parseOperation(specFile, "Test_Get");
        assertThat(result2).isNotNull();
        assertThat(result2.operationId()).isEqualTo("Test_Get");
        
        // Cache size should remain the same (reused)
        assertThat(parser.getCacheSize()).isEqualTo(1);
        
        // Clear cache
        parser.clearCache();
        assertThat(parser.getCacheSize()).isEqualTo(0);
    }
    
    @Test
    void testParseOperationWithMissingExternalReference() throws Exception {
        String specWithMissingRef = """
            {
              "swagger": "2.0",
              "info": {
                "title": "Test API",
                "version": "1.0.0"
              },
              "paths": {
                "/test": {
                  "get": {
                    "operationId": "Test_GetWithMissingRef",
                    "description": "Test operation with missing external ref",
                    "parameters": [
                      {
                        "$ref": "./missing.json#/parameters/MissingParameter"
                      }
                    ],
                    "responses": {
                      "200": {
                        "description": "Success"
                      }
                    }
                  }
                }
              }
            }
            """;
        
        Path specPath = tempDir.resolve("test.json");
        Files.writeString(specPath, specWithMissingRef);
        
        SpecificationFile specFile = new SpecificationFile(
            specPath, 
            new ApiVersion("1.0.0", LocalDate.now(), true)
        );
        
        // Should still parse the operation but skip the missing parameter
        OperationSpec result = parser.parseOperation(specFile, "Test_GetWithMissingRef");
        
        assertThat(result).isNotNull();
        assertThat(result.operationId()).isEqualTo("Test_GetWithMissingRef");
        
        // Missing external reference should result in empty parameters list
        // (since the parameter couldn't be resolved)
        assertThat(result.parameters()).isEmpty();
    }
}