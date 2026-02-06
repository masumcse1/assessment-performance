package com.complianx.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class CityMapper {
    
    private static final Logger logger = LoggerFactory.getLogger(CityMapper.class);
    
    private final Map<String, String> cityMappings;
    
    public CityMapper() {
        this.cityMappings = new HashMap<>();
        loadCityMappings();
    }
    
    /**
     * Maps a city name to its canonical English form if a mapping exists,
     * otherwise returns the original name in uppercase.
     * 
     * @param cityName the city name to map (case-insensitive)
     * @return the canonical English name in uppercase, or original name in uppercase if no mapping
     */
    public String mapCity(String cityName) {
        if (cityName == null || cityName.trim().isEmpty()) {
            return cityName;
        }
        
        String upperCityName = cityName.trim().toUpperCase();
        return cityMappings.getOrDefault(upperCityName, upperCityName);
    }
    
    /**
     * Loads city mappings from the classpath resource cityMappings.txt.
     * Expected format: input_name,canonical_english
     */
    private void loadCityMappings() {
        try (InputStream is = getClass().getResourceAsStream("/cityMappings.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            
            if (is == null) {
                logger.warn("City mappings file not found: /cityMappings.txt");
                return;
            }
            
            String line;
            int lineCount = 0;
            int mappingCount = 0;
            
            while ((line = reader.readLine()) != null) {
                lineCount++;
                line = line.trim();
                
                // Skip empty lines and header
                if (line.isEmpty() || line.startsWith("input_name,") || line.startsWith("#")) {
                    continue;
                }
                
                parseLine(line, lineCount);
                mappingCount++;
            }
            
            logger.trace("Loaded {} city mappings from cityMappings.txt ({} total lines processed)",
                       mappingCount, lineCount);
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to load city mappings from /cityMappings.txt", e);
        }
    }
    
    /**
     * Parses a single line from the city mappings file.
     * Expected format: input_name,canonical_english
     */
    private void parseLine(String line, int lineNumber) {
        int commaIndex = line.indexOf(',');
        if (commaIndex == -1) {
            logger.warn("Invalid line format in cityMappings.txt at line {}: {}", lineNumber, line);
            return;
        }
        
        String inputName = line.substring(0, commaIndex).trim();
        String canonicalName = line.substring(commaIndex + 1).trim();
        
        if (inputName.isEmpty() || canonicalName.isEmpty()) {
            logger.warn("Empty input or canonical name in cityMappings.txt at line {}: {}", lineNumber, line);
            return;
        }
        
        // Store both names in uppercase for case-insensitive matching
        cityMappings.put(inputName.toUpperCase(), canonicalName.toUpperCase());
    }
    
    /**
     * Returns statistics about the loaded city mappings.
     */
    public String getStatistics() {
        return String.format("CityMapper Statistics: %d city mappings loaded", cityMappings.size());
    }
    
    /**
     * Returns the number of loaded mappings.
     */
    public int getMappingCount() {
        return cityMappings.size();
    }
}