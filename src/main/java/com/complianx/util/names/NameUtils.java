package com.complianx.util.names;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class NameUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(NameUtils.class);
    
    private static final Set<String> NOBILITY_TITLES = new HashSet<>();
    
    static {
        loadNobilityTitles();
    }

    private static void loadNobilityTitles() {
        loadNobilityTitles("/nobility-titles.txt");
        loadNobilityTitles("/titles.txt");
    }
    private static void loadNobilityTitles(String resourcePath) {

        
        try (InputStream is = NameUtils.class.getResourceAsStream(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            
            if (is == null) {
                logger.warn("Nobility titles file not found: {}", resourcePath);
                return;
            }
            
            String line;
            int titleCount = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                NOBILITY_TITLES.add(line.toUpperCase());
                titleCount++;
            }
            
            logger.info("Loaded {} nobility titles from {}", titleCount, resourcePath);
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to load nobility titles from " + resourcePath, e);
        }
    }
    
    public static boolean isNobiliaryParticle(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        
        String upperName = name.trim().toUpperCase();
        return NOBILITY_TITLES.contains(upperName);
    }
}