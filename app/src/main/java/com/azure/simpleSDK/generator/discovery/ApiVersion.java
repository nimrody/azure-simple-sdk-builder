package com.azure.simpleSDK.generator.discovery;

import java.time.LocalDate;

public class ApiVersion {
    private final String versionString;
    private final LocalDate date;
    private final boolean stable;
    
    public ApiVersion(String versionString, LocalDate date, boolean stable) {
        this.versionString = versionString;
        this.date = date;
        this.stable = stable;
    }
    
    public String getVersionString() {
        return versionString;
    }
    
    public LocalDate getDate() {
        return date;
    }
    
    public boolean isStable() {
        return stable;
    }
    
    @Override
    public String toString() {
        return "ApiVersion{" +
                "versionString='" + versionString + '\'' +
                ", date=" + date +
                ", stable=" + stable +
                '}';
    }
}