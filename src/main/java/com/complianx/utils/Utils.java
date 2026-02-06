package com.complianx.utils;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.text.Normalizer;
import java.util.Map;

public class Utils {

    private static final double FUZZY_SIMILARITY_THRESHOLD = 0.94;
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    private static final Map<String, String> SPECIAL_CHAR_MAPPING_EXTENDED = Map.of(
        // German Umlaute
        "Ä", "AE",
        "Ö", "OE", 
        "Ü", "UE",
        "ß", "SS",
        // Nordic characters - extended forms
        "Æ", "AE",
        "Ø", "OE"
    );
    
    private static final Map<String, String> SPECIAL_CHAR_MAPPING_SIMPLE = Map.of(
        // German Umlaute - same as extended for consistency
        "Ä", "A",
        "Ö", "O", 
        "Ü", "U",
        "ß", "SS",
        // Nordic characters - simple forms
        "Æ", "A",
        "Ø", "O",
        // Polish characters
        "Ł", "L",
        // Other special characters that don't normalize properly
        "Đ", "D"
    );
    
    /**
     * Compares two strings for equality in a diacritic-insensitive, case-insensitive manner.
     *
     * Both parameters may contain diacritical marks (e.g. "Ä", "é", etc.).
     * German Umlaute are treated specially:
     *   - "Ä" is considered equal to "A" or "AE",
     *   - "Ö" is equal to "O" or "OE",
     *   - "Ü" is equal to "U" or "UE",
     *   - "ß" is equal to "SS".
     *
     * Other diacritical marks are removed (e.g. "é" or "è" becomes "E").
     *
     * @param first  The first string that may contain diacritical marks.
     * @param second  The second string that may contain diacritical marks.
     * @return boolean True if the strings are considered equal, false otherwise.
     */
    public static boolean equalWithoutDiacriticals(String first, String second) {
        if (first == null && second == null) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        
        // Convert both strings to uppercase
        String firstUpper = first.toUpperCase();
        String secondUpper = second.toUpperCase();
        
        // Version 1: Use Unicode normalization directly
        String firstV1 = normalizeString(firstUpper);
        String secondV1 = normalizeString(secondUpper);
        
        // Version 2: First replace special characters with extended forms (e.g., Ä->AE, Ø->OE)
        String firstExtended = replaceSpecialChars(firstUpper, SPECIAL_CHAR_MAPPING_EXTENDED);
        String secondExtended = replaceSpecialChars(secondUpper, SPECIAL_CHAR_MAPPING_EXTENDED);
        String firstV2 = normalizeString(firstExtended);
        String secondV2 = normalizeString(secondExtended);
        
        // Version 3: First replace special characters with simple forms (e.g., Ä->A, Ø->O)  
        String firstSimple = replaceSpecialChars(firstUpper, SPECIAL_CHAR_MAPPING_SIMPLE);
        String secondSimple = replaceSpecialChars(secondUpper, SPECIAL_CHAR_MAPPING_SIMPLE);
        String firstV3 = normalizeString(firstSimple);
        String secondV3 = normalizeString(secondSimple);
        
        // Remove any non-alphabetic characters
        String normFirst1 = removeNonAlphabetic(firstV1);
        String normFirst2 = removeNonAlphabetic(firstV2);
        String normFirst3 = removeNonAlphabetic(firstV3);
        String normSecond1 = removeNonAlphabetic(secondV1);
        String normSecond2 = removeNonAlphabetic(secondV2);
        String normSecond3 = removeNonAlphabetic(secondV3);
        
        // Check if any combination matches (all versions against each other)
        return normFirst1.equals(normSecond1) || normFirst1.equals(normSecond2) || normFirst1.equals(normSecond3) ||
               normFirst2.equals(normSecond1) || normFirst2.equals(normSecond2) || normFirst2.equals(normSecond3) ||
               normFirst3.equals(normSecond1) || normFirst3.equals(normSecond2) || normFirst3.equals(normSecond3);
    }

    public static String NormalizeWithoutDiacriticals(String input) {
        String firstUpper = input.toUpperCase();
        String firstExtended = replaceSpecialChars(firstUpper, SPECIAL_CHAR_MAPPING_EXTENDED);
        String firstV2 = normalizeString(firstExtended);;
        return removeNonAlphabetic(firstV2);
    }

    public static String NormalizeWithoutDiacriticalsPreserveSpecialCharacters(String input) {
        String firstUpper = input.toUpperCase();
        String firstExtended = replaceSpecialChars(firstUpper, SPECIAL_CHAR_MAPPING_EXTENDED);
        return  normalizeString(firstExtended);
    }
    
    /**
     * Normalizes a string by removing diacritical marks using Unicode normalization.
     */
    private static String normalizeString(String input) {
        // Normalize to NFD (Canonical Decomposition) to separate base characters from combining marks
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        // Remove all combining diacritical marks (Unicode category Mn)
        return normalized.replaceAll("\\p{M}", "");
    }
    
    /**
     * Replaces special characters (German umlauts, Nordic chars, etc.) with their equivalents.
     */
    private static String replaceSpecialChars(String input, Map<String, String> mapping) {
        String result = input;
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
    
    /**
     * Removes all non-alphanumeric characters from a string, preserving digits.
     */
    private static String removeNonAlphabetic(String input) {
        return input.replaceAll("[^A-Z0-9]", "");
    }

    /**
     * Compares two strings using both exact matching (with diacritics normalization)
     * and strict fuzzy matching using JaroWinkler similarity.
     *
     * This method first tries exact matching using equalWithoutDiacriticals.
     * If exact matching fails, it applies strict fuzzy matching with JaroWinkler
     * similarity algorithm and requires a minimum similarity score of 0.9 (90%).
     *
     * The fuzzy matching only applies to strings of length 4 or more to avoid
     * false positives on very short strings.
     *
     * @param first The first string
     * @param second The second string
     * @return true if the strings match exactly or have JaroWinkler similarity >= 0.9
     */
    public static boolean matchesWithStrictFuzzy(String first, String second) {
        // First try exact matching with diacritics normalization
        if (equalWithoutDiacriticals(first, second)) {
            return true;
        }

        // If either string is null, exact matching already handled it
        if (first == null || second == null) {
            return false;
        }

        // Normalize both strings the same way as equalWithoutDiacriticals
        String firstNormalized = NormalizeWithoutDiacriticals(first);
        String secondNormalized = NormalizeWithoutDiacriticals(second);

        // Only apply fuzzy matching for strings of reasonable length
        // to avoid false positives on very short strings
        int minLength = Math.min(firstNormalized.length(), secondNormalized.length());
        if (minLength < 4) {
            return false; // Too short for reliable fuzzy matching
        }

        // Calculate JaroWinkler similarity (0.0 to 1.0)
        double similarity = JARO_WINKLER.apply(firstNormalized, secondNormalized);

        // Require strict similarity threshold
        return similarity >= FUZZY_SIMILARITY_THRESHOLD;
    }
}