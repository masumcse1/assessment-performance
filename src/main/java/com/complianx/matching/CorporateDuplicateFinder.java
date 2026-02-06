package com.complianx.matching;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

/**
 * Ultra-optimized corporate duplicate finder.
 * Uses blocking, word-level pre-filter, and parallel processing.
 */
public class CorporateDuplicateFinder {

    private final CorporateNameMatcher corporateNameMatcher = new CorporateNameMatcher();

    /**
     * Find duplicates in the given list of company names.
     */
    public List<MatchResult> findDuplicates(List<String> companyNames) {

        // Step 1: Normalize all names
        List<String> normalizedNames = companyNames.stream()
                .map(this::normalizeName)
                .toList();

        // Step 2: Build blocks using first letters (max 4 letters)
        Map<String, List<Integer>> blocks = buildBlocks(normalizedNames);

        // Step 3: Thread-safe collection for storing matches
        ConcurrentLinkedQueue<MatchResult> matches = new ConcurrentLinkedQueue<>();

        // Step 4: Parallel block processing
        blocks.values().parallelStream().forEach(indices -> processBlock(indices, companyNames, normalizedNames, matches));

        return new ArrayList<>(matches);
    }

    /**
     * Build blocks for candidate filtering.
     */
    private Map<String, List<Integer>> buildBlocks(List<String> normalizedNames) {
        Map<String, List<Integer>> blocks = new HashMap<>();
        for (int i = 0; i < normalizedNames.size(); i++) {
            String key = blockKey(normalizedNames.get(i));
            blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        return blocks;
    }

    private void processBlock(List<Integer> indices, List<String> companyNames,
                              List<String> normalizedNames, ConcurrentLinkedQueue<MatchResult> matches) {

        int blockSize = indices.size();

        // Stream over all indices i
        IntStream.range(0, blockSize).forEach(i -> {
            int idx1 = indices.get(i);
            String name1 = companyNames.get(idx1);
            List<String> words1 = List.of(normalizedNames.get(idx1).split("\\s+"));

            // Stream over j > i for each i
            IntStream.range(i + 1, blockSize).forEach(j -> {
                int idx2 = indices.get(j);
                String name2 = companyNames.get(idx2);
                List<String> words2 = List.of(normalizedNames.get(idx2).split("\\s+"));

                // Cheap pre-filter
                if (!shareWord(words1, words2)) return;

                // Expensive matcher
                Double score = corporateNameMatcher.matchCorporateNames(name1, name2);
                if (score != null && score > 0.85) {
                    matches.add(new MatchResult(name1, name2, score));
                }
            });
        });
    }


    /**
     * Word-level pre-filter: check if two names share any word or abbreviation.
     */
    private boolean shareWord(List<String> words1, List<String> words2) {
        return words1.stream().anyMatch(w1 ->
                words2.stream().anyMatch(w2 ->
                        w1.equals(w2) || w1.startsWith(w2) || w2.startsWith(w1)
                )
        );
    }

    /**
     * Normalize a company name for blocking.
     */
    private String normalizeName(String name) {
        if (name == null || name.isEmpty()) return "";

        String cleaned = LegalFormCleaner.cleanCompanyName(name).toUpperCase();
        cleaned = cleaned.replaceAll("[^A-Z\\s]", " "); // keep letters and space

        // remove common stopwords
        List<String> words = Arrays.stream(cleaned.split("\\s+"))
                .filter(w -> !List.of("AND", "THE", "OF", "&").contains(w))
                .toList();

        return String.join(" ", words);
    }

    /**
     * Generate a block key from normalized name: first letters of words (max 4 letters)
     */
    private String blockKey(String normalizedName) {
        if (normalizedName.isEmpty()) return "";
        StringBuilder key = new StringBuilder();
        for (String word : normalizedName.split("\\s+")) {
            if (!word.isEmpty()) key.append(word.charAt(0));
        }
        return key.length() > 4 ? key.substring(0, 4) : key.toString();
    }

    /**
     * Match result class.
     */
    public static class MatchResult {
        public final String name1;
        public final String name2;
        public final double score;

        public MatchResult(String name1, String name2, double score) {
            this.name1 = name1;
            this.name2 = name2;
            this.score = score;
        }
    }
}
