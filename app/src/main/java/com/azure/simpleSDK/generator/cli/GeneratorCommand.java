package com.azure.simpleSDK.generator.cli;

import com.azure.simpleSDK.generator.discovery.SpecificationFinder;
import com.azure.simpleSDK.generator.parser.OpenApiParser;
import picocli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(
    name = "azure-sdk-generator",
    description = "Generate Azure Simple SDK for specified operations",
    mixinStandardHelpOptions = true,
    version = "1.0.0-SNAPSHOT"
)
public class GeneratorCommand implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(GeneratorCommand.class);
    
    @CommandLine.Parameters(
        description = "Operation IDs to generate (e.g., VirtualNetworkGateways_List)",
        arity = "1..*"
    )
    private List<String> operationIds;
    
    @CommandLine.Option(
        names = {"-s", "--specs-dir"},
        description = "Path to Azure REST API specifications directory (default: azure-rest-api-specs/specification)",
        defaultValue = "azure-rest-api-specs/specification"
    )
    private String specsDirectory;
    
    @CommandLine.Option(
        names = {"-o", "--output-dir"},
        description = "Output directory for generated SDK code (default: sdk/src/main/java)",
        defaultValue = "sdk/src/main/java"
    )
    private String outputDirectory;
    
    @CommandLine.Option(
        names = {"-p", "--package"},
        description = "Base package name for generated code (default: com.azure.simpleSDK)",
        defaultValue = "com.azure.simpleSDK"
    )
    private String basePackage;
    
    @Override
    public Integer call() throws Exception {
        logger.info("Generating SDK for operations: {}", operationIds);
        logger.info("Specs directory: {}", specsDirectory);
        logger.info("Output directory: {}", outputDirectory);
        logger.info("Base package: {}", basePackage);
        
        try {
            Path specsPath = Paths.get(specsDirectory);
            Path outputPath = Paths.get(outputDirectory);
            
            SpecificationFinder finder = new SpecificationFinder(specsPath);
            OpenApiParser parser = new OpenApiParser();
            
            for (String operationId : operationIds) {
                logger.info("Processing operation: {}", operationId);
                
                var specFile = finder.findOperationSpec(operationId);
                if (specFile == null) {
                    logger.error("Could not find specification for operation: {}", operationId);
                    return 1;
                }
                
                logger.info("Found specification: {}", specFile.getPath());
                
                var operationSpec = parser.parseOperation(specFile, operationId);
                if (operationSpec == null) {
                    logger.error("Could not parse operation {} from {}", operationId, specFile.getPath());
                    return 1;
                }
                
                logger.info("Successfully parsed operation: {} - {}", operationId, operationSpec.description());
            }
            
            logger.info("All operations processed successfully");
            return 0;
            
        } catch (Exception e) {
            logger.error("Error during SDK generation", e);
            return 1;
        }
    }
}