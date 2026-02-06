package com.complianx.processing;

import com.complianx.utils.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import no.priv.garshol.duke.comparators.Levenshtein;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class LegalFormRetriever {

    private static final double SIMILARITY_THRESHOLD = 0.85;
    private static final int MAX_RECURSION_DEPTH = 50;
    private static final Levenshtein levenshtein = new Levenshtein();
    protected static List<ReplacementEntry> cachedReplacements = null;
    private static Map<String, List<LegalFormEntry>> cachedLegalForms = null;

    /**
     * Enum to distinguish between full and partial matches for prioritization
     */
    private enum MatchType {
        FULL_MATCH,    // Entire word matches the pattern
        PARTIAL_MATCH, // Only suffix of word matches pattern
        NO_MATCH       // No match
    }

    /**
     * Class to hold match result with type information
     */
    private static class MatchResult {
        final MatchType type;
        final boolean matches;
        final int splitPoint; // For partial matches, where to split the word

        MatchResult(MatchType type, boolean matches, int splitPoint) {
            this.type = type;
            this.matches = matches;
            this.splitPoint = splitPoint;
        }

        static MatchResult noMatch() {
            return new MatchResult(MatchType.NO_MATCH, false, -1);
        }

        static MatchResult fullMatch() {
            return new MatchResult(MatchType.FULL_MATCH, true, 0);
        }

        static MatchResult partialMatch(int splitPoint) {
            return new MatchResult(MatchType.PARTIAL_MATCH, true, splitPoint);
        }
    }

    protected static class ReplacementEntry {
        final List<String> searchWords;
        final String replacement;
        final int wordCount;

        ReplacementEntry(String searchString, String replacement) {
            // Clean and normalize search string using the same logic as clean method but without loading replacements
            List<String> cleanedSearchWords = cleanSearchString(searchString);
            this.searchWords = cleanedSearchWords;
            this.replacement = replacement.toLowerCase();
            this.wordCount = this.searchWords.size();
        }

        /**
         * Determine the match type for a given word against the first search word in this pattern
         */
        MatchResult getFirstWordMatchType(String word) {
            if (searchWords.isEmpty()) {
                return MatchResult.noMatch();
            }
            return LegalFormRetriever.endsWithFuzzyTyped(word, searchWords.get(0));
        }

        /**
         * Check if this pattern would result in a full match for single-word inputs
         */
        boolean isFullMatchForSingleWord(String word) {
            if (wordCount != 1) {
                return false; // Multi-word patterns can't be full matches for single words
            }
            MatchResult result = getFirstWordMatchType(word);
            return result.matches && result.type == MatchType.FULL_MATCH;
        }
    }

    /**
     * Class to hold legal form information from JSON
     */
    private static class LegalFormEntry {
        final String legalFormId;
        final List<String> cleanedShortName; // cleaned words from clean() method
        final String originalShortName;      // for length comparison
        final int shortNameLength;           // original length for prioritization
        final boolean isActuallyShortened;   // true if short_name is different from long_name

        LegalFormEntry(String legalFormId, String shortName, String longName) {
            this.legalFormId = legalFormId;
            this.originalShortName = shortName;
            this.shortNameLength = shortName.length();
            this.cleanedShortName = cleanSearchString(shortName); // Use cleanSearchString to avoid circular dependency
            // Check if short name is actually shortened (different from long name)
            this.isActuallyShortened = longName != null && !shortName.equalsIgnoreCase(longName);
        }
    }

    /**
     * Clean search string for replacement entries (without loading replacements to avoid circular dependency)
     */
    private static List<String> cleanSearchString(String searchString) {
        if (searchString == null || searchString.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Transform to lower case
        String processed = searchString.toLowerCase();

        // Replace all occurrences of '&' with ' u '
        processed = processed.replace("&", " u ");

        // Remove specified characters: ;$.-,_#+*§"!?
        processed = processed.replaceAll("[;$.,_#+*§\"!?-]", " ");

        // Remove everything within brackets (...) including the brackets
        processed = processed.replaceAll("\\([^)]*\\)", " ");

        // Remove multiple spaces
        processed = processed.replaceAll("\\s+", " ").trim();

        // Split into words
        if (processed.isEmpty()) {
            return new ArrayList<>();
        }

        String[] words = processed.split("\\s+");

        // Apply Utils.NormalizeWithoutDiacriticals to each word and convert back to lowercase
        List<String> cleanedWords = new ArrayList<>();
        for (String word : words) {
            String normalized = Utils.NormalizeWithoutDiacriticals(word);
            if (normalized != null && !normalized.isEmpty()) {
                cleanedWords.add(normalized.toLowerCase());
            }
        }

        // Replace "und" with "u"
        cleanedWords = cleanedWords.stream()
            .map(word -> word.equals("und") ? "u" : word)
            .collect(Collectors.toList());

        return cleanedWords;
    }

    protected static synchronized void loadReplacementsIfNeeded() {
        if (cachedReplacements != null) {
            return;
        }

        List<ReplacementEntry> replacements = new ArrayList<>();

        try (InputStream is = LegalFormRetriever.class.getClassLoader().getResourceAsStream("legal_form_replacements.csv");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    String searchString = parts[0].trim();
                    String replacement = parts[1].trim();
                    ReplacementEntry entry = new ReplacementEntry(searchString, replacement);
                    replacements.add(entry);
                }
            }

            // Sort by word count (descending) then by total length (descending)
            replacements.sort((a, b) -> {
                int cmp = Integer.compare(b.wordCount, a.wordCount);
                if (cmp != 0) return cmp;

                int lengthA = a.searchWords.stream().mapToInt(String::length).sum();
                int lengthB = b.searchWords.stream().mapToInt(String::length).sum();
                return Integer.compare(lengthB, lengthA);
            });

            cachedReplacements = replacements;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load legal_form_replacements.csv", e);
        }
    }

    /**
     * Load legal forms from JSON file and cache them grouped by country
     */
    private static synchronized void loadLegalFormsIfNeeded() {
        if (cachedLegalForms != null) {
            return;
        }

        Map<String, List<LegalFormEntry>> legalFormsByCountry = new HashMap<>();
        ObjectMapper objectMapper = new ObjectMapper();

        try (InputStream is = LegalFormRetriever.class.getClassLoader().getResourceAsStream("legal_forms.json")) {
            JsonNode rootNode = objectMapper.readTree(is);

            for (JsonNode node : rootNode) {
                String legalFormId = node.get("legal_form_id").asText();
                String shortName = node.get("short_name").asText();
                String longName = node.has("long_name") ? node.get("long_name").asText() : null;
                String country = node.get("country").asText();

                LegalFormEntry entry = new LegalFormEntry(legalFormId, shortName, longName);
                legalFormsByCountry.computeIfAbsent(country, k -> new ArrayList<>()).add(entry);
            }

            // Sort each country's legal forms by cleaned short name total length (descending) for prioritization
            for (List<LegalFormEntry> entries : legalFormsByCountry.values()) {
                entries.sort((a, b) -> {
                    int cleanedLengthA = a.cleanedShortName.stream().mapToInt(String::length).sum();
                    int cleanedLengthB = b.cleanedShortName.stream().mapToInt(String::length).sum();
                    return Integer.compare(cleanedLengthB, cleanedLengthA);
                });
            }

            cachedLegalForms = legalFormsByCountry;

        } catch (IOException e) {
            throw new RuntimeException("Failed to load legal_forms.json", e);
        }
    }

    /**
     * Clean method that performs the initial text processing
     */
    protected static List<String> clean(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Transform to lower case
        String processed = name.toLowerCase();

        // Replace all occurrences of '&' with ' u '
        processed = processed.replace("&", " u ");

        processed = processed.replace("+", " u ");

        // Remove specified characters: ;$.-,_#+*§"!?
        processed = processed.replaceAll("[;$.,_#+*§\"!?-]", " ");

        // Remove everything within brackets (...) including the brackets
        processed = processed.replaceAll("\\([^)]*\\)", " ");

        // Remove multiple spaces
        processed = processed.replaceAll("\\s+", " ").trim();

        // Split into words
        if (processed.isEmpty()) {
            return new ArrayList<>();
        }

        String[] words = processed.split("\\s+");

        // Apply Utils.NormalizeWithoutDiacriticals to each word and convert back to lowercase
        List<String> cleanedWords = new ArrayList<>();
        for (String word : words) {
            String normalized = Utils.NormalizeWithoutDiacriticals(word);
            if (normalized != null && !normalized.isEmpty()) {
                cleanedWords.add(normalized.toLowerCase());
            }
        }

        // Replace "und" with "u"
        cleanedWords = cleanedWords.stream()
            .map(word -> word.equals("und") ? "u" : word)
            .collect(Collectors.toList());

        return cleanedWords;
    }

    /**
     * Class to track how original words were split during normalization
     *
     * Key insight: We track which original word each current normalized word came from.
     * When splits happen, we update the mapping and shift indices as needed.
     */
    protected static class SplitTracker {
        // Maps each current normalized word index to the original word index it came from
        protected final Map<Integer, Integer> normalizedToOriginal = new HashMap<>();

        /**
         * Initialize tracker for cleaned words (1:1 mapping)
         */
        void initializeMapping(int originalWordCount) {
            normalizedToOriginal.clear();
            for (int i = 0; i < originalWordCount; i++) {
                normalizedToOriginal.put(i, i);
            }
        }

        /**
         * Record that a word at normalizedIndex was split, creating a new word at normalizedIndex+1
         * Both words come from the same original word.
         */
        void recordSplit(int normalizedIndex) {
            Integer originalIndex = normalizedToOriginal.get(normalizedIndex);
            if (originalIndex == null) {
                throw new IllegalStateException("No mapping found for normalized index " + normalizedIndex);
            }

            // Shift all mappings after the split point by +1
            Map<Integer, Integer> shifted = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : normalizedToOriginal.entrySet()) {
                int normIdx = entry.getKey();
                int origIdx = entry.getValue();

                if (normIdx > normalizedIndex) {
                    shifted.put(normIdx + 1, origIdx);
                } else {
                    shifted.put(normIdx, origIdx);
                }
            }

            // Add mapping for the new split word
            shifted.put(normalizedIndex + 1, originalIndex);

            normalizedToOriginal.clear();
            normalizedToOriginal.putAll(shifted);
        }

        /**
         * Record that words were removed from startIndex to endIndex (inclusive)
         */
        void recordRemoval(int startIndex, int endIndex) {
            int removeCount = endIndex - startIndex + 1;

            // Remove mappings for deleted indices
            for (int i = startIndex; i <= endIndex; i++) {
                normalizedToOriginal.remove(i);
            }

            // Shift remaining mappings down
            Map<Integer, Integer> shifted = new HashMap<>();
            for (Map.Entry<Integer, Integer> entry : normalizedToOriginal.entrySet()) {
                int normIdx = entry.getKey();
                int origIdx = entry.getValue();

                if (normIdx > endIndex) {
                    shifted.put(normIdx - removeCount, origIdx);
                } else {
                    shifted.put(normIdx, origIdx);
                }
            }

            normalizedToOriginal.clear();
            normalizedToOriginal.putAll(shifted);
        }

        /**
         * Get the original word index for a normalized word index
         */
        Integer getOriginalIndex(int normalizedIndex) {
            return normalizedToOriginal.get(normalizedIndex);
        }

        /**
         * Get all normalized indices that came from the given original word
         */
        List<Integer> getNormalizedIndices(int originalIndex) {
            List<Integer> result = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : normalizedToOriginal.entrySet()) {
                if (entry.getValue().equals(originalIndex)) {
                    result.add(entry.getKey());
                }
            }
            Collections.sort(result);
            return result;
        }

        /**
         * Check if an original word was split (maps to multiple normalized indices)
         */
        boolean wasOriginalWordSplit(int originalIndex) {
            return getNormalizedIndices(originalIndex).size() > 1;
        }

        /**
         * Check if an original word contributed to a legal form match
         */
        boolean didOriginalWordContributeToMatch(int originalIndex, int matchStartIndex, int matchEndIndex) {
            List<Integer> normalizedIndices = getNormalizedIndices(originalIndex);

            for (Integer normalizedIndex : normalizedIndices) {
                if (normalizedIndex >= matchStartIndex && normalizedIndex <= matchEndIndex) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Create a deep copy of this tracker
         */
        SplitTracker copy() {
            SplitTracker copy = new SplitTracker();
            copy.normalizedToOriginal.putAll(this.normalizedToOriginal);
            return copy;
        }

        /**
         * Get current size (number of normalized words)
         */
        int getCurrentSize() {
            return normalizedToOriginal.isEmpty() ? 0 : normalizedToOriginal.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        }

        /**
         * Debug method to print current state
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SplitTracker{");
            normalizedToOriginal.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sb.append(entry.getKey()).append("->").append(entry.getValue()).append(" "));
            sb.append("}");
            return sb.toString();
        }
    }

    /**
     * Main normalize method that returns collection of normalized string variations
     */
    public List<List<String>> normalize(String name) {
        loadReplacementsIfNeeded();

        // Clean the input
        List<String> cleanedWords = clean(name);

        if (cleanedWords.isEmpty()) {
            return Collections.singletonList(new ArrayList<>());
        }

        // Process replacements with safety net
        Set<List<String>> processedStates = new HashSet<>();
        return processReplacements(cleanedWords, processedStates, 0);
    }

    /**
     * Version of normalize that also returns split tracking information
     */
    protected NormalizeResult normalizeWithTracking(String name) {
        loadReplacementsIfNeeded();

        // Clean the input
        List<String> cleanedWords = clean(name);

        if (cleanedWords.isEmpty()) {
            return new NormalizeResult(Collections.singletonList(new ArrayList<>()), new SplitTracker());
        }

        // Initialize split tracker with 1:1 mapping for cleaned words
        SplitTracker splitTracker = new SplitTracker();
        splitTracker.initializeMapping(cleanedWords.size());

        // Process replacements with tracking
        Set<List<String>> processedStates = new HashSet<>();
        List<List<String>> normalizedResults = processReplacementsWithTracking(cleanedWords, splitTracker, processedStates, 0);

        return new NormalizeResult(normalizedResults, splitTracker);
    }

    /**
     * Result class that contains normalized strings and split tracking
     */
    protected static class NormalizeResult {
        final List<List<String>> normalizedAlternatives;
        final SplitTracker splitTracker;

        NormalizeResult(List<List<String>> normalizedAlternatives, SplitTracker splitTracker) {
            this.normalizedAlternatives = normalizedAlternatives;
            this.splitTracker = splitTracker;
        }
    }

    /**
     * Process replacements with split tracking
     */
    private List<List<String>> processReplacementsWithTracking(List<String> words, SplitTracker splitTracker, Set<List<String>> processedStates, int depth) {
        // Safety net 1: Check for cycle detection
        if (processedStates.contains(words)) {
            List<List<String>> results = new ArrayList<>();
            results.add(new ArrayList<>(words));
            return results;
        }

        // Safety net 2: Check recursion depth limit
        if (depth > MAX_RECURSION_DEPTH) {
            List<List<String>> results = new ArrayList<>();
            results.add(new ArrayList<>(words));
            return results;
        }

        // Add current state to processed states
        processedStates.add(new ArrayList<>(words));

        List<List<String>> results = new ArrayList<>();

        // Try to find a replacement
        boolean foundReplacement = false;
        for (ReplacementEntry entry : cachedReplacements) {
            List<ReplacementResult> matchResults = tryReplacementWithTracking(words, entry, splitTracker);
            if (!matchResults.isEmpty()) {
                foundReplacement = true;
                // Take the first match and recurse
                ReplacementResult firstMatch = matchResults.get(0);

                // Update the original tracker with the changes from this replacement
                splitTracker.normalizedToOriginal.clear();
                splitTracker.normalizedToOriginal.putAll(firstMatch.updatedTracker.normalizedToOriginal);

                if (firstMatch.resultWords.isEmpty()) {
                    results.add(firstMatch.resultWords);
                } else {
                    List<List<String>> subResults = processReplacementsWithTracking(firstMatch.resultWords, splitTracker, processedStates, depth + 1);
                    results.addAll(subResults);
                }
                break; // Take the first replacement found
            }
        }

        if (!foundReplacement) {
            // No replacements found, return the words as-is
            results.add(new ArrayList<>(words));
        }

        return results;
    }

    /**
     * Result of trying a replacement with split tracking
     */
    protected static class ReplacementResult {
        final List<String> resultWords;
        final SplitTracker updatedTracker;

        ReplacementResult(List<String> resultWords, SplitTracker updatedTracker) {
            this.resultWords = resultWords;
            this.updatedTracker = updatedTracker;
        }
    }

    /**
     * Try to apply a replacement entry with split tracking
     */
    protected List<ReplacementResult> tryReplacementWithTracking(List<String> words, ReplacementEntry entry, SplitTracker splitTracker) {
        List<ReplacementResult> results = new ArrayList<>();

        if (words.size() < entry.wordCount) {
            return results;  // Not enough words for this replacement
        }

        // Start from the rightmost position where match is possible
        for (int endIdx = words.size() - 1; endIdx >= entry.wordCount - 1; endIdx--) {
            if (matchesAtPosition(words, endIdx, entry)) {
                int startIdx = endIdx - (entry.wordCount - 1);

                // Create a copy of the tracker to work with
                SplitTracker newTracker = splitTracker.copy();

                // Build the result step by step, tracking changes
                List<String> result = new ArrayList<>();

                // Add words before the match (no changes to tracking needed)
                for (int i = 0; i < startIdx; i++) {
                    result.add(words.get(i));
                }

                // Handle potential partial match on the first word (leftmost in pattern)
                String firstMatchWord = words.get(startIdx);
                String searchFirstWord = entry.searchWords.get(0);

                boolean splitDetected = false;
                int splitPoint = findBestSuffixMatch(firstMatchWord, searchFirstWord);
                if (splitPoint > 0) {
                    // SPLIT DETECTED: Split the word and track it
                    String head = firstMatchWord.substring(0, splitPoint);
                    result.add(head);
                    splitDetected = true;
                }

                // Add the replacement
                result.add(entry.replacement);

                // Add words after the match
                for (int i = endIdx + 1; i < words.size(); i++) {
                    result.add(words.get(i));
                }

                // Now record the split AFTER all words have been added to the result
                if (splitDetected) {
                    // We need to record that the original word at startIdx was split
                    // The result now contains the split parts at positions startIdx and startIdx+1
                    int originalWordIndex = startIdx; // This is the index in the ORIGINAL words array
                    int resultSplitIndex = startIdx; // This is where the split head was placed in result

                    // Record the split at the result index where the head was placed
                    newTracker.recordSplit(resultSplitIndex);
                }

                results.add(new ReplacementResult(result, newTracker));
            }
        }

        return results;
    }


    /**
     * Process replacements recursively from right to left with full vs partial match prioritization
     * Includes safety net to prevent infinite recursion through cycle detection and depth limiting
     *
     * @param words The list of words to process
     * @param processedStates Set of word combinations already processed to detect cycles
     * @param depth Current recursion depth for depth limiting
     * @return List of possible replacement results
     */
    private List<List<String>> processReplacements(List<String> words, Set<List<String>> processedStates, int depth) {
        // Safety net 1: Check for cycle detection
        if (processedStates.contains(words)) {
            // Cycle detected - return current state to prevent infinite recursion
            List<List<String>> results = new ArrayList<>();
            results.add(new ArrayList<>(words));
            return results;
        }

        // Safety net 2: Check recursion depth limit
        if (depth > MAX_RECURSION_DEPTH) {
            // Max depth exceeded - return current state to prevent stack overflow
            List<List<String>> results = new ArrayList<>();
            results.add(new ArrayList<>(words));
            return results;
        }

        // Add current state to processed states (defensive copy to avoid mutation issues)
        processedStates.add(new ArrayList<>(words));

        List<List<String>> results = new ArrayList<>();

        // Group matches by word count and match type
        Map<Integer, List<List<String>>> fullMatchesByWordCount = new HashMap<>();
        Map<Integer, List<List<String>>> partialMatchesByWordCount = new HashMap<>();

        for (ReplacementEntry entry : cachedReplacements) {
            List<List<String>> matchResults = tryReplacementWithType(words, entry);
            if (!matchResults.isEmpty()) {
                // Determine if this is a full or partial match for prioritization
                boolean isFullMatch = isFullMatchForPattern(words, entry);

                if (isFullMatch) {
                    fullMatchesByWordCount.computeIfAbsent(entry.wordCount, k -> new ArrayList<>()).addAll(matchResults);
                } else {
                    partialMatchesByWordCount.computeIfAbsent(entry.wordCount, k -> new ArrayList<>()).addAll(matchResults);
                }
            }
        }

        // Prioritize: 1) word count, 2) full matches over partial matches
        if (!fullMatchesByWordCount.isEmpty()) {
            // Process full matches first
            int maxWordCount = fullMatchesByWordCount.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<List<String>> bestMatches = fullMatchesByWordCount.get(maxWordCount);

            if (!bestMatches.isEmpty()) {
                List<String> firstBestMatch = bestMatches.get(0);
                if (firstBestMatch.isEmpty()) {
                    results.add(firstBestMatch);
                } else {
                    List<List<String>> subResults = processReplacements(firstBestMatch, processedStates, depth + 1);
                    results.addAll(subResults);
                }
            }
        } else if (!partialMatchesByWordCount.isEmpty()) {
            // Only process partial matches if no full matches available
            int maxWordCount = partialMatchesByWordCount.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<List<String>> bestMatches = partialMatchesByWordCount.get(maxWordCount);

            if (!bestMatches.isEmpty()) {
                List<String> firstBestMatch = bestMatches.get(0);
                if (firstBestMatch.isEmpty()) {
                    results.add(firstBestMatch);
                } else {
                    List<List<String>> subResults = processReplacements(firstBestMatch, processedStates, depth + 1);
                    results.addAll(subResults);
                }
            }
        } else {
            // No matches found, return the words as-is
            results.add(new ArrayList<>(words));
        }

        return results;
    }

    /**
     * Determine if a replacement would be a full match (no word splitting) for the given words
     */
    private boolean isFullMatchForPattern(List<String> words, ReplacementEntry entry) {
        if (words.size() != entry.wordCount) {
            return false; // Different word counts can't be full matches
        }

        // For single-word patterns, check if it's a full word match
        if (entry.wordCount == 1 && words.size() == 1) {
            return entry.isFullMatchForSingleWord(words.get(0));
        }

        // For multi-word patterns, all words must match exactly (this is already handled by fuzzy matching)
        return true;
    }

    /**
     * Enhanced version of tryReplacement that works with the new typing system
     */
    private List<List<String>> tryReplacementWithType(List<String> words, ReplacementEntry entry) {
        // Use the existing tryReplacement logic for now
        return tryReplacement(words, entry);
    }

    /**
     * Try to apply a replacement entry to the words list from right to left
     */
    private List<List<String>> tryReplacement(List<String> words, ReplacementEntry entry) {
        List<List<String>> results = new ArrayList<>();

        if (words.size() < entry.wordCount) {
            return results;  // Not enough words for this replacement
        }

        // Start from the rightmost position where match is possible
        for (int endIdx = words.size() - 1; endIdx >= entry.wordCount - 1; endIdx--) {
            if (matchesAtPosition(words, endIdx, entry)) {
                // Create result with replacement
                List<String> result = new ArrayList<>();

                int startIdx = endIdx - (entry.wordCount - 1);

                // Add words before the match
                for (int i = 0; i < startIdx; i++) {
                    result.add(words.get(i));
                }

                // Handle potential partial match on the first word (leftmost in pattern)
                String firstMatchWord = words.get(startIdx);
                String searchFirstWord = entry.searchWords.get(0);

                int splitPoint = findBestSuffixMatch(firstMatchWord, searchFirstWord);
                if (splitPoint > 0) {
                    // Partial match - split the word
                    String head = firstMatchWord.substring(0, splitPoint);
                    result.add(head);
                }

                // Add the replacement
                result.add(entry.replacement);

                // Add words after the match
                for (int i = endIdx + 1; i < words.size(); i++) {
                    result.add(words.get(i));
                }

                results.add(result);
            }
        }

        return results;
    }

    /**
     * Check if a word ends with another word using Levenshtein similarity
     * Returns match type information to prioritize full matches over partial matches
     */
    private static MatchResult endsWithFuzzyTyped(String word, String suffix) {
        // First check for full word match (highest priority)
        double fullSimilarity = levenshtein.compare(word, suffix);
        if (fullSimilarity >= SIMILARITY_THRESHOLD) {
            return MatchResult.fullMatch();
        }

        // Then check for partial suffix match (lower priority)
        if (suffix.length() <= word.length()) {
            String wordEnd = word.substring(word.length() - suffix.length());
            double similarity = levenshtein.compare(wordEnd, suffix);
            if (similarity >= SIMILARITY_THRESHOLD) {
                int splitPoint = word.length() - suffix.length();
                return MatchResult.partialMatch(splitPoint);
            }
        }

        return MatchResult.noMatch();
    }

    /**
     * Backward compatibility method
     */
    private boolean endsWithFuzzy(String word, String suffix) {
        return endsWithFuzzyTyped(word, suffix).matches;
    }

    /**
     * Find the best matching suffix in a word and return the split point
     * Returns -1 if no good match is found
     */
    protected int findBestSuffixMatch(String word, String suffix) {
        if (suffix.length() > word.length()) {
            return -1;
        }

        // Try exact match first
        if (word.endsWith(suffix)) {
            return word.length() - suffix.length();
        }

        // Try fuzzy match at the end
        String wordEnd = word.substring(word.length() - suffix.length());
        double similarity = levenshtein.compare(wordEnd, suffix);
        if (similarity >= SIMILARITY_THRESHOLD) {
            return word.length() - suffix.length();
        }

        return -1;
    }

    /**
     * Check if two words match using Levenshtein similarity
     */
    private boolean matchesFuzzy(String word1, String word2) {
        double similarity = levenshtein.compare(word1, word2);
        return similarity >= SIMILARITY_THRESHOLD;
    }

    /**
     * Check if replacement entry matches at given position (from right to left)
     */
    protected boolean matchesAtPosition(List<String> words, int endIdx, ReplacementEntry entry) {
        if (endIdx < entry.wordCount - 1) {
            return false;
        }

        int startIdx = endIdx - (entry.wordCount - 1);

        // For the first word in the pattern, check if it matches end of word using fuzzy matching
        String firstWord = words.get(startIdx);
        String searchFirstWord = entry.searchWords.get(0);

        if (searchFirstWord.length() > 2) {
            if (!endsWithFuzzy(firstWord, searchFirstWord)) {
                return false;
            }
        } else {
            if (!firstWord.endsWith(searchFirstWord)) {
                return false;
            }
        }

        // Check all remaining words - they must match completely using fuzzy matching
        for (int i = 1; i < entry.wordCount; i++) {
            if (entry.searchWords.get(i).length() > 2) {

                if (!matchesFuzzy(words.get(startIdx + i), entry.searchWords.get(i))) {
                    return false;
                }
            } else {
                if (!words.get(startIdx + i).equals(entry.searchWords.get(i))) {
                    return false;
                }
            }
        }

        return true;
    }

    private static class MatchWindow {
        final int startIndex;
        final int endIndex;
        final int trailingCharsInEndToken;

        MatchWindow(int startIndex, int endIndex, int trailingCharsInEndToken) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.trailingCharsInEndToken = trailingCharsInEndToken;
        }
    }

    protected static class LegalFormMatch {
        final LegalFormEntry legalFormEntry;
        final List<String> normalizedAlternative;
        final int matchStartIndex;
        final int matchEndIndex;
        final boolean fallbackMatch;
        final int fallbackCharsToTrim;
        final int trailingCharsInEndToken;
        final SplitTracker splitTracker;

        LegalFormMatch(LegalFormEntry legalFormEntry, List<String> normalizedAlternative, int matchStartIndex, int matchEndIndex, int trailingCharsInEndToken) {
            this(legalFormEntry, normalizedAlternative, matchStartIndex, matchEndIndex, false, 0, trailingCharsInEndToken, null);
        }

        LegalFormMatch(LegalFormEntry legalFormEntry, List<String> normalizedAlternative, int matchStartIndex, int matchEndIndex, boolean fallbackMatch, int fallbackCharsToTrim) {
            this(legalFormEntry, normalizedAlternative, matchStartIndex, matchEndIndex, fallbackMatch, fallbackCharsToTrim, 0, null);
        }

        LegalFormMatch(LegalFormEntry legalFormEntry, List<String> normalizedAlternative, int matchStartIndex, int matchEndIndex, boolean fallbackMatch, int fallbackCharsToTrim, int trailingCharsInEndToken) {
            this(legalFormEntry, normalizedAlternative, matchStartIndex, matchEndIndex, fallbackMatch, fallbackCharsToTrim, trailingCharsInEndToken, null);
        }

        LegalFormMatch(LegalFormEntry legalFormEntry, List<String> normalizedAlternative, int matchStartIndex, int matchEndIndex, boolean fallbackMatch, int fallbackCharsToTrim, int trailingCharsInEndToken, SplitTracker splitTracker) {
            this.legalFormEntry = legalFormEntry;
            this.normalizedAlternative = new ArrayList<>(normalizedAlternative);
            this.matchStartIndex = matchStartIndex;
            this.matchEndIndex = matchEndIndex;
            this.fallbackMatch = fallbackMatch;
            this.fallbackCharsToTrim = fallbackCharsToTrim;
            this.trailingCharsInEndToken = trailingCharsInEndToken;
            this.splitTracker = splitTracker;
        }
    }

    /**
     * Retrieve legal form ID for a corporate name and country
     * Returns the legal_form_id of the longest matching short_name across all normalized alternatives
     */
    public String retrieveLegalForm(String corporateName, String countryCode) {
        loadLegalFormsIfNeeded();

        List<LegalFormEntry> countryForms = cachedLegalForms.get(countryCode);
        if (countryForms == null) {
            return null; // No legal forms for this country
        }

        List<List<String>> alternatives = normalize(corporateName);
        LegalFormMatch match = findBestLegalFormMatch(alternatives, countryForms);
        return match != null ? match.legalFormEntry.legalFormId : null;
    }

    /**
     * Return the normalized company name without the matched legal form suffix.
     */
    public String companyNameWithoutLegalForm(String corporateName, String countryCode) {
        loadLegalFormsIfNeeded();

        List<LegalFormEntry> countryForms = cachedLegalForms.get(countryCode);

        // Use normalization with split tracking
        NormalizeResult normalizeResult = normalizeWithTracking(corporateName);
        List<List<String>> alternatives = normalizeResult.normalizedAlternatives;

        if (countryForms == null || alternatives.isEmpty()) {
            return joinWords(alternatives.isEmpty() ? Collections.emptyList() : alternatives.get(0));
        }

        LegalFormMatch match = findBestLegalFormMatchWithTracking(alternatives, countryForms, normalizeResult.splitTracker);
        if (match == null) {
            return joinWords(alternatives.get(0));
        }

        List<String> words = new ArrayList<>(match.normalizedAlternative);

        if (match.fallbackMatch) {
            applyFallbackRemoval(words, match);
        } else {
            applyPrimaryRemoval(words, match);
        }

        List<String> restoredWords = restoreOriginalTokens(corporateName, words, match);
        return joinWords(restoredWords);
    }

    /**
     * Find the best legal form match with split tracking information
     */
    private LegalFormMatch findBestLegalFormMatchWithTracking(List<List<String>> alternatives, List<LegalFormEntry> countryForms, SplitTracker splitTracker) {
        // Collect all matches from all alternatives
        List<LegalFormMatch> allMatches = new ArrayList<>();

        for (List<String> alternative : alternatives) {
            for (LegalFormEntry entry : countryForms) {
                MatchWindow window = findMatchWindow(alternative, entry.cleanedShortName);
                if (window != null) {
                    allMatches.add(new LegalFormMatch(entry, alternative, window.startIndex, window.endIndex, false, 0, window.trailingCharsInEndToken, splitTracker));
                    // Removed break to continue finding all matches
                }
            }
        }

        // Apply new prioritization logic
        LegalFormMatch bestMatch = selectBestMatchFromCandidates(allMatches);
        if (bestMatch != null) {
            return bestMatch;
        }

        // Fallback mechanism using endsWith() matching when primary algorithm fails
        for (List<String> alternative : alternatives) {
            String combinedInput = String.join("", alternative);

            for (LegalFormEntry entry : countryForms) {
                if (entry.isActuallyShortened) {
                    continue;
                }

                String normalizedShortName = String.join("", entry.cleanedShortName);
                if (!normalizedShortName.isEmpty() && combinedInput.endsWith(normalizedShortName)) {
                    LegalFormMatch fallbackMatch = buildFallbackMatchWithTracking(alternative, entry, normalizedShortName, splitTracker);
                    if (fallbackMatch != null) {
                        return fallbackMatch;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Build fallback match with split tracking
     */
    private LegalFormMatch buildFallbackMatchWithTracking(List<String> alternative, LegalFormEntry entry, String normalizedShortName, SplitTracker splitTracker) {
        int suffixLength = normalizedShortName.length();
        int remaining = suffixLength;
        int index = alternative.size();
        int charsToTrim = 0;

        while (remaining > 0 && index > 0) {
            String token = alternative.get(index - 1);
            int tokenLength = token.length();

            if (tokenLength <= remaining) {
                String expected = normalizedShortName.substring(remaining - tokenLength, remaining);
                if (!token.equals(expected)) {
                    return null;
                }
                remaining -= tokenLength;
                index--;
            } else {
                String expected = normalizedShortName.substring(0, remaining);
                String tokenSuffix = token.substring(tokenLength - remaining);
                if (!tokenSuffix.equals(expected)) {
                    return null;
                }
                charsToTrim = remaining;
                index--;
                remaining = 0;
            }
        }

        if (remaining > 0) {
            return null;
        }

        return new LegalFormMatch(entry, alternative, index, alternative.size() - 1, true, charsToTrim, 0, splitTracker);
    }

    private void applyPrimaryRemoval(List<String> words, LegalFormMatch match) {
        if (match.matchStartIndex < 0 || match.matchStartIndex >= words.size()) {
            return;
        }

        List<String> sourceTokens = match.normalizedAlternative;
        if (match.matchStartIndex >= sourceTokens.size()) {
            return;
        }

        String target = String.join("", match.legalFormEntry.cleanedShortName);
        int baseEnd = Math.min(match.matchEndIndex, sourceTokens.size() - 1);

        StringBuilder baseBuilder = new StringBuilder();
        for (int i = match.matchStartIndex; i <= baseEnd; i++) {
            baseBuilder.append(sourceTokens.get(i));
        }

        String baseString = baseBuilder.toString();
        if (!target.isEmpty() && !baseString.startsWith(target) && target.length() > baseString.length()) {
            // Ensure minimal coverage of target even if replacements shortened tokens unexpectedly
            for (int i = baseEnd + 1; i < sourceTokens.size() && baseString.length() < target.length(); i++) {
                baseBuilder.append(sourceTokens.get(i));
                baseEnd = i;
                baseString = baseBuilder.toString();
            }
        }

        String trailingFromBase = "";
        if (baseString.length() > target.length()) {
            trailingFromBase = baseString.substring(target.length());
        }

        int extendedEnd = baseEnd;
        StringBuilder extendedBuilder = new StringBuilder(baseString);

        String lastCleanedToken = match.legalFormEntry.cleanedShortName.isEmpty()
            ? ""
            : match.legalFormEntry.cleanedShortName.get(match.legalFormEntry.cleanedShortName.size() - 1);

        for (int i = baseEnd + 1; i < sourceTokens.size(); i++) {
            String token = sourceTokens.get(i);
            String candidate = extendedBuilder.toString() + token;
            if (!candidate.startsWith(target)) {
                break;
            }

            String extraBeyondTarget = candidate.substring(target.length());
            if (!extraBeyondTarget.isEmpty()) {
                if (lastCleanedToken.isEmpty()) {
                    break;
                }

                if (!lastCleanedToken.startsWith(extraBeyondTarget) && !lastCleanedToken.endsWith(extraBeyondTarget)) {
                    break;
                }
            }

            extendedBuilder.append(token);
            extendedEnd = i;
        }

        int removalCount = Math.max(0, extendedEnd - match.matchStartIndex + 1);
        for (int i = 0; i < removalCount && match.matchStartIndex < words.size(); i++) {
            words.remove(match.matchStartIndex);
        }

        if (!trailingFromBase.isEmpty()) {
            words.add(match.matchStartIndex, trailingFromBase);
        }
    }

    private void applyFallbackRemoval(List<String> words, LegalFormMatch match) {
        for (int i = match.matchEndIndex; i > match.matchStartIndex; i--) {
            words.remove(i);
        }

        if (match.matchStartIndex >= words.size()) {
            return;
        }

        if (match.fallbackCharsToTrim <= 0) {
            words.remove(match.matchStartIndex);
            return;
        }

        String token = words.get(match.matchStartIndex);
        if (token.length() <= match.fallbackCharsToTrim) {
            words.remove(match.matchStartIndex);
        } else {
            words.set(match.matchStartIndex, token.substring(0, token.length() - match.fallbackCharsToTrim));
        }
    }

    protected List<String> restoreOriginalTokens(String originalName, List<String> normalizedWords, LegalFormMatch match) {
        if (normalizedWords.isEmpty()) {
            return normalizedWords;
        }

        List<String> originalWords = clean(originalName);
        if (originalWords.isEmpty()) {
            return normalizedWords;
        }

        // If no match, we can safely restore all words
        if (match == null) {
            return restoreWordsWithoutMatch(originalWords, normalizedWords);
        }

        // If we have split tracking information, use it
        if (match.splitTracker != null) {
            return restoreWithSplitTracking(originalWords, normalizedWords, match);
        }

        // Fallback: Simple heuristic approach
        // Check if the match involved any split words by comparing lengths
        List<String> cleanedOriginal = clean(originalName);
        if (cleanedOriginal.size() < match.normalizedAlternative.size()) {
            // Words were split during normalization
            // If any of the split parts are in the match range, don't restore
            return normalizedWords;
        }

        // Otherwise, use the original restoration logic
        return restoreWordsWithoutMatch(originalWords, normalizedWords);
    }

    private List<String> restoreWithSplitTracking(List<String> originalWords, List<String> normalizedWords, LegalFormMatch match) {
        List<String> restored = new ArrayList<>();
        int normalizedIndex = 0;

        for (int originalIndex = 0; originalIndex < originalWords.size(); originalIndex++) {
            if (normalizedIndex >= normalizedWords.size()) {
                break;
            }

            String originalWord = originalWords.get(originalIndex);

            // Check if this original word contributed to the legal form match
            if (match.splitTracker.didOriginalWordContributeToMatch(originalIndex, match.matchStartIndex, match.matchEndIndex)) {
                // This original word contributed to the match - don't restore it
                // Add the remaining normalized tokens for this word
                List<Integer> normalizedIndices = match.splitTracker.getNormalizedIndices(originalIndex);
                int remainingTokens = 0;
                for (Integer normalizedIdx : normalizedIndices) {
                    if (normalizedIdx < match.matchStartIndex || normalizedIdx > match.matchEndIndex) {
                        remainingTokens++;
                    }
                }

                for (int i = 0; i < remainingTokens && normalizedIndex < normalizedWords.size(); i++) {
                    restored.add(normalizedWords.get(normalizedIndex));
                    normalizedIndex++;
                }
            } else {
                // This original word did NOT contribute to the match - safe to restore
                List<Integer> normalizedIndices = match.splitTracker.getNormalizedIndices(originalIndex);

                // Try to match the normalized tokens back to this original word
                StringBuilder combined = new StringBuilder();
                for (int i = 0; i < normalizedIndices.size() && normalizedIndex < normalizedWords.size(); i++) {
                    combined.append(normalizedWords.get(normalizedIndex + i));
                }

                if (wordsEquivalentForRestoration(originalWord, combined.toString())) {
                    restored.add(originalWord);
                } else {
                    // Fallback to normalized tokens
                    for (int i = 0; i < normalizedIndices.size() && normalizedIndex < normalizedWords.size(); i++) {
                        restored.add(normalizedWords.get(normalizedIndex + i));
                    }
                }

                normalizedIndex += normalizedIndices.size();
            }
        }

        // Add any remaining normalized words
        while (normalizedIndex < normalizedWords.size()) {
            restored.add(normalizedWords.get(normalizedIndex));
            normalizedIndex++;
        }

        return restored;
    }

    private List<String> restoreWordsWithoutMatch(List<String> originalWords, List<String> normalizedWords) {
        List<String> restored = new ArrayList<>();
        int normalizedIndex = 0;

        for (String original : originalWords) {
            if (normalizedIndex >= normalizedWords.size()) {
                break;
            }

            StringBuilder combined = new StringBuilder();
            int localIndex = normalizedIndex;
            int bestMatchIndex = -1;

            while (localIndex < normalizedWords.size()) {
                combined.append(normalizedWords.get(localIndex));
                String combinedValue = combined.toString();

                if (wordsEquivalentForRestoration(original, combinedValue)) {
                    bestMatchIndex = localIndex;
                    if (combinedValue.length() >= original.length()) {
                        break;
                    }
                }

                if (combinedValue.length() > original.length() + 2 && bestMatchIndex >= 0) {
                    break;
                }

                if (combinedValue.length() > original.length() + 10) {
                    break;
                }

                localIndex++;
            }

            if (bestMatchIndex >= 0) {
                restored.add(original);
                normalizedIndex = bestMatchIndex + 1;
            }
        }

        if (normalizedIndex == normalizedWords.size() && !restored.isEmpty()) {
            return restored;
        }

        return normalizedWords;
    }

    private boolean wordsEquivalentForRestoration(String original, String normalizedCandidate) {
        if (original.equals(normalizedCandidate)) {
            return true;
        }

        if (!original.isEmpty() && original.startsWith(normalizedCandidate)) {
            return true;
        }

        if (!normalizedCandidate.isEmpty() && normalizedCandidate.startsWith(original)) {
            return true;
        }

        double similarity = levenshtein.compare(original, normalizedCandidate);
        return similarity >= 0.85;
    }

    private String joinWords(List<String> words) {
        if (words.isEmpty()) {
            return "";
        }
        return String.join(" ", words);
    }

    /**
     * Check if two legal form matches overlap in their position ranges
     */
    private boolean matchesOverlap(LegalFormMatch match1, LegalFormMatch match2) {
        return match1.matchEndIndex >= match2.matchStartIndex &&
               match2.matchEndIndex >= match1.matchStartIndex;
    }

    /**
     * Check if one legal form contains another (e.g., "GmbH & Co. KG" contains "KG")
     */
    private boolean legalFormContainsAnother(LegalFormMatch longer, LegalFormMatch shorter) {
        String longerCleaned = String.join("", longer.legalFormEntry.cleanedShortName);
        String shorterCleaned = String.join("", shorter.legalFormEntry.cleanedShortName);
        return longerCleaned.contains(shorterCleaned);
    }

    /**
     * Select the best match from a list of candidate matches.
     * Prioritization rules:
     * 1. If two matches from the same alternative overlap, prefer the longer one (by shortNameLength)
     * 2. If one match contains another (e.g., "GmbH & Co. KG" contains "KG"), prefer the longer one
     * 3. If matches don't overlap and neither contains the other, prefer the one at the end (highest endIndex)
     * 4. If tie in endIndex, prefer longer shortNameLength
     */
    private LegalFormMatch selectBestMatchFromCandidates(List<LegalFormMatch> allMatches) {
        if (allMatches.isEmpty()) {
            return null;
        }

        if (allMatches.size() == 1) {
            return allMatches.get(0);
        }

        // Step 1: Filter out matches that are contained in or overlap with longer ones from the SAME alternative
        List<LegalFormMatch> filteredMatches = new ArrayList<>();
        for (LegalFormMatch candidate : allMatches) {
            boolean isEliminated = false;
            for (LegalFormMatch other : allMatches) {
                if (candidate != other &&
                    candidate.normalizedAlternative.equals(other.normalizedAlternative)) {

                    boolean overlaps = matchesOverlap(candidate, other);

                    // Calculate cleaned length (total chars in cleanedShortName)
                    int candidateCleanedLength = candidate.legalFormEntry.cleanedShortName.stream().mapToInt(String::length).sum();
                    int otherCleanedLength = other.legalFormEntry.cleanedShortName.stream().mapToInt(String::length).sum();
                    boolean candidateShorterByCleaned = candidateCleanedLength < otherCleanedLength;

                    // Eliminate candidate if:
                    // a) It overlaps with a longer match (by cleaned length), OR
                    // b) It's shorter (by cleaned length) and contained in the other match
                    if (overlaps && candidateShorterByCleaned) {
                        isEliminated = true;
                        break;
                    }

                    if (candidateShorterByCleaned && legalFormContainsAnother(other, candidate)) {
                        isEliminated = true;
                        break;
                    }
                }
            }
            if (!isEliminated) {
                filteredMatches.add(candidate);
            }
        }

        // Step 2: Among remaining matches, select the rightmost one
        LegalFormMatch bestMatch = filteredMatches.get(0);
        for (LegalFormMatch match : filteredMatches) {
            if (match.matchEndIndex > bestMatch.matchEndIndex) {
                bestMatch = match;
            } else if (match.matchEndIndex == bestMatch.matchEndIndex) {
                // If tie in position, prefer longer cleaned length
                int matchCleanedLength = match.legalFormEntry.cleanedShortName.stream().mapToInt(String::length).sum();
                int bestCleanedLength = bestMatch.legalFormEntry.cleanedShortName.stream().mapToInt(String::length).sum();
                if (matchCleanedLength > bestCleanedLength) {
                    bestMatch = match;
                }
            }
        }

        return bestMatch;
    }

    private LegalFormMatch findBestLegalFormMatch(List<List<String>> alternatives, List<LegalFormEntry> countryForms) {
        // Collect all matches from all alternatives
        List<LegalFormMatch> allMatches = new ArrayList<>();

        for (List<String> alternative : alternatives) {
            for (LegalFormEntry entry : countryForms) {
                MatchWindow window = findMatchWindow(alternative, entry.cleanedShortName);
                if (window != null) {
                    allMatches.add(new LegalFormMatch(entry, alternative, window.startIndex, window.endIndex, window.trailingCharsInEndToken));
                    // Removed break to continue finding all matches
                }
            }
        }

        // Apply new prioritization logic
        LegalFormMatch bestMatch = selectBestMatchFromCandidates(allMatches);
        if (bestMatch != null) {
            return bestMatch;
        }

        // Fallback mechanism using endsWith() matching when primary algorithm fails
        for (List<String> alternative : alternatives) {
            String combinedInput = String.join("", alternative);

            for (LegalFormEntry entry : countryForms) {
                if (entry.isActuallyShortened) {
                    continue;
                }

                String normalizedShortName = String.join("", entry.cleanedShortName);
                if (!normalizedShortName.isEmpty() && combinedInput.endsWith(normalizedShortName)) {
                    LegalFormMatch fallbackMatch = buildFallbackMatch(alternative, entry, normalizedShortName);
                    if (fallbackMatch != null) {
                        return fallbackMatch;
                    }
                }
            }
        }

        return null;
    }

    private LegalFormMatch buildFallbackMatch(List<String> alternative, LegalFormEntry entry, String normalizedShortName) {
        int suffixLength = normalizedShortName.length();
        int remaining = suffixLength;
        int index = alternative.size();
        int charsToTrim = 0;

        while (remaining > 0 && index > 0) {
            String token = alternative.get(index - 1);
            int tokenLength = token.length();

            if (tokenLength <= remaining) {
                String expected = normalizedShortName.substring(remaining - tokenLength, remaining);
                if (!token.equals(expected)) {
                    return null;
                }
                remaining -= tokenLength;
                index--;
            } else {
                String expected = normalizedShortName.substring(0, remaining);
                String tokenSuffix = token.substring(tokenLength - remaining);
                if (!tokenSuffix.equals(expected)) {
                    return null;
                }
                charsToTrim = remaining;
                index--;
                remaining = 0;
            }
        }

        if (remaining > 0) {
            return null;
        }

        return new LegalFormMatch(entry, alternative, index, alternative.size() - 1, true, charsToTrim);
    }

    private MatchWindow findMatchWindow(List<String> normalizedCompanyName, List<String> cleanedShortName) {
        if (normalizedCompanyName.isEmpty() || cleanedShortName.isEmpty()) {
            return null;
        }

        String target = String.join("", cleanedShortName);

        for (int i = normalizedCompanyName.size() - 1; i >= 0; i--) {
            StringBuilder combined = new StringBuilder();
            int consumedLength = 0;

            for (int j = i; j < normalizedCompanyName.size(); j++) {
                String token = normalizedCompanyName.get(j);
                combined.append(token);
                consumedLength += token.length();

                if (consumedLength >= target.length()) {
                    String combinedStr = combined.toString();
                    if (combinedStr.startsWith(target)) {
                        int lengthBeforeCurrentToken = consumedLength - token.length();
                        int charsConsumedFromCurrent = target.length() - lengthBeforeCurrentToken;
                        int trailingChars = token.length() - charsConsumedFromCurrent;
                        if (trailingChars < 0) {
                            trailingChars = 0;
                        }
                        return new MatchWindow(i, j, trailingChars);
                    }
                    break; // Either exact match failed or target not aligned
                }
            }
        }

        return null;
    }
}
