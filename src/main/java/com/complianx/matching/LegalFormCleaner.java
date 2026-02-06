package com.complianx.matching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for cleaning legal forms from company names.
 * Loads legal forms from JSON and text files and provides methods to remove them from company names.
 */
public class LegalFormCleaner {
    
    private static final Logger logger = LoggerFactory.getLogger(LegalFormCleaner.class);
    
    private static final Set<String> legalForms = new HashSet<>();
    private static final Pattern BRACKET_PATTERN = Pattern.compile("\\s*\\([^)]+\\)\\s*$");
    
    static {
        loadLegalForms();
    }
    
    /**
     * Load legal forms from both JSON and text files
     */
    private static void loadLegalForms() {
        try {
            // Load from JSON file (legal_forms.json)
            loadFromJsonFile();
            
            // Load from text file (addition_legal_forms.txt)
            loadFromTextFile();
            
            logger.info("Loaded {} legal forms from resource files", legalForms.size());
            
        } catch (Exception e) {
            logger.error("Failed to load legal forms", e);
        }
    }
    
    /**
     * Load legal forms from the JSON file.
     * Since we don't have a JSON library, we'll parse it manually for the specific format.
     */
    private static void loadFromJsonFile() throws IOException {
        try (InputStream is = LegalFormCleaner.class.getClassLoader().getResourceAsStream("legal_forms.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            
            if (is == null) {
                logger.warn("legal_forms.json not found in resources");
                return;
            }
            
            String line;
            int jsonEntries = 0;
            
            // Simple regex patterns to extract short_name and long_name values
            Pattern shortNamePattern = Pattern.compile("\"short_name\"\\s*:\\s*\"([^\"]+)\"");
            Pattern longNamePattern = Pattern.compile("\"long_name\"\\s*:\\s*\"([^\"]+)\"");
            
            while ((line = reader.readLine()) != null) {
                // Extract short_name
                Matcher shortMatcher = shortNamePattern.matcher(line);
                if (shortMatcher.find()) {
                    String shortName = shortMatcher.group(1).trim();
                    if (!shortName.isEmpty() && !shortName.equalsIgnoreCase("null")) {
                        legalForms.add(shortName.toUpperCase());
                    }
                }
                
                // Extract long_name
                Matcher longMatcher = longNamePattern.matcher(line);
                if (longMatcher.find()) {
                    String longName = longMatcher.group(1).trim();
                    if (!longName.isEmpty() && !longName.equalsIgnoreCase("null")) {
                        legalForms.add(longName.toUpperCase());
                        jsonEntries++;
                    }
                }
            }
            
            logger.info("Loaded {} entries from legal_forms.json", jsonEntries);
        }
    }
    
    /**
     * Load legal forms from the text file
     */
    private static void loadFromTextFile() throws IOException {
        try (InputStream is = LegalFormCleaner.class.getClassLoader().getResourceAsStream("addition_legal_forms.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            
            if (is == null) {
                logger.warn("addition_legal_forms.txt not found in resources");
                return;
            }
            
            String line;
            int textEntries = 0;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    legalForms.add(line.toUpperCase());
                    textEntries++;
                }
            }
            
            logger.info("Loaded {} entries from addition_legal_forms.txt", textEntries);
        }
    }
    
    /**
     * Clean a company name by removing legal forms and content in brackets.
     * 
     * @param companyName the company name to clean
     * @return cleaned company name, or original if null/empty
     */
    public static String cleanCompanyNameAndRemoveLegalForm(String companyName) {
        if (companyName == null) {
            return null;
        }
        if (companyName.trim().isEmpty()) {
            return "";
        }
        
        String cleaned = companyName.trim();
        
        // Step 1: Remove content in brackets at the end
        cleaned = removeBrackets(cleaned);

        cleaned = removePunctuation(cleaned);
        
        // Step 2: Remove legal forms from the end
        cleaned = removeLegalForms(cleaned);
        
        return cleaned.trim();
    }

    /**
     * Clean a company name by removing content in brackets and punctuation.
     *
     * @param companyName the company name to clean
     * @return cleaned company name, or original if null/empty
     */
    public static String cleanCompanyName(String companyName) {
        if (companyName == null) {
            return null;
        }
        if (companyName.trim().isEmpty()) {
            return "";
        }

        String cleaned = companyName.trim();

        // Step 1: Remove content in brackets at the end
        cleaned = removeBrackets(cleaned);

        cleaned = removePunctuation(cleaned);

        return cleaned.trim();
    }
    
    /**
     * Remove content in brackets from the end of the company name
     */
    private static String removeBrackets(String name) {
        Matcher matcher = BRACKET_PATTERN.matcher(name);
        if (matcher.find()) {
            return name.substring(0, matcher.start()).trim();
        }
        return name;
    }


    // Alternative implementation using multiple replace calls
    public static String removePunctuation(String input) {
        if (input == null) {
            return null;
        }
        return input.replace(",", "")
                .replace(":", "")
                .replace(";", "");
    }
    
    /**
     * Remove legal forms from the end of the company name
     */
    private static String removeLegalForms(String name) {
        String upperName = name.toUpperCase();
        String result = name;
        
        // Keep trying to remove legal forms from the end until no more can be removed
        boolean removed;
        do {
            removed = false;
            for (String legalForm : legalForms) {
                // Check if the name ends with this legal form (with word boundary)
                String pattern = "\\s+" + Pattern.quote(legalForm) + "\\s*$";
                Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(result);
                
                if (m.find()) {
                    result = result.substring(0, m.start()).trim();
                    removed = true;
                    break;
                }
                
                // Also check if the name ends exactly with the legal form
                if (result.toUpperCase().endsWith(" " + legalForm)) {
                    result = result.substring(0, result.length() - legalForm.length() - 1).trim();
                    removed = true;
                    break;
                } else if (result.toUpperCase().equals(legalForm)) {
                    // If the entire name is just a legal form, return empty
                    result = "";
                    removed = true;
                    break;
                }
            }
        } while (removed);
        
        return result;
    }
    
    /**
     * Get the number of loaded legal forms (for testing purposes)
     */
    public static int getLegalFormsCount() {
        return legalForms.size();
    }
    
    /**
     * Check if a string is a known legal form (for testing purposes)
     */
    public static boolean isLegalForm(String form) {
        return form != null && legalForms.contains(form.toUpperCase());
    }
}