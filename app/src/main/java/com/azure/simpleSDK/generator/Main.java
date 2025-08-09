package com.azure.simpleSDK.generator;

import com.azure.simpleSDK.generator.cli.GeneratorCommand;
import picocli.CommandLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        logger.info("Starting Azure Simple SDK Generator");
        
        CommandLine cmd = new CommandLine(new GeneratorCommand());
        int exitCode = cmd.execute(args);
        
        logger.info("Generator completed with exit code: {}", exitCode);
        System.exit(exitCode);
    }
}