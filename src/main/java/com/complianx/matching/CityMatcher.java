package com.complianx.matching;

import com.complianx.util.names.NameUtils;
import com.complianx.utils.Utils;
import no.priv.garshol.duke.comparators.JaroWinkler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class CityMatcher {

    private static final Logger logger = LoggerFactory.getLogger(CityMatcher.class);

    private final JaroWinkler jaroWinkler;
    private final CityMapper cityMapper;

    public CityMatcher() {
        this.jaroWinkler = new JaroWinkler();
        this.cityMapper = new CityMapper();
    }

    /**
     * Matches two place strings and returns the probability that they refer to the same place.
     * Only performs matching if both sources provide non-empty places.
     *
     * @param place1 the first place
     * @param place2 the second place
     * @return probability between 0.0 and 1.0, or null if places don't match or are empty
     */
    public Double matchCityName(String place1, String place2) {
        return matchCityName(place1, place2, null, null);
    }

    public Double matchCityName(String place1, String place2, String fullAddress1, String fullAddress2) {
        // Only perform if both sources provide a place
        if (place1 == null || place1.trim().isEmpty() ||
            place2 == null || place2.trim().isEmpty()) {
            return null;
        }

        // Convert both values to uppercase
        String upperPlace1 = place1.trim().toUpperCase();
        String upperPlace2 = place2.trim().toUpperCase();

        // Compare with equalWithoutDiacriticals
        if (Utils.equalWithoutDiacriticals(upperPlace1, upperPlace2)) {
            return 1.0;
        }

        String cleanPlace1 = Utils.NormalizeWithoutDiacriticalsPreserveSpecialCharacters(upperPlace1);
        String cleanPlace2 = Utils.NormalizeWithoutDiacriticalsPreserveSpecialCharacters(upperPlace2);

        if (cleanPlace1.startsWith(cleanPlace2) || cleanPlace2.startsWith(cleanPlace1)) {
            String shorter = cleanPlace1;
            String longer = cleanPlace2;

            if (shorter.length() > longer.length()) {
                longer = cleanPlace1;
                shorter = cleanPlace2;
            }

            String rest = longer.substring(shorter.length()).trim();
            if (rest.startsWith("AM ") || rest.startsWith("AN ") || rest.startsWith("BEI ") || rest.startsWith("A.") || rest.startsWith("IM") ||
                    rest.startsWith("I.") || rest.startsWith("VOR ") || rest.startsWith("V.") || rest.startsWith("(") || rest.startsWith("OT ")
                    || rest.startsWith(",") || rest.startsWith("-") || rest.startsWith("A D") || rest.startsWith("AN") ) {
                return 0.99;
            }
        }

        String mappedPlace1 = cityMapper.mapCity(upperPlace1);
        String mappedPlace2 = cityMapper.mapCity(upperPlace2);

        if (mappedPlace1.equals(mappedPlace2)) {
            return 0.99;
        }

        // Try to match on abbreviations
        Double nameMatcherScore = matchIndividualWordsOfTheCity(mappedPlace1, mappedPlace2);

        // Immediate return if nameMatcherScore is very high
        if (nameMatcherScore != null && nameMatcherScore > 0.9) {
            return nameMatcherScore;
        }

        boolean isNameMatcherScoreSignificant = nameMatcherScore != null && nameMatcherScore > 0.85;

        // Jaro-Winkler comparison on cleaned names
        double jaroWinklerScore = jaroWinkler.compare(cleanPlace1, cleanPlace2);

        // Immediate return if score is very high
        if (jaroWinklerScore > 0.9) {
            return jaroWinklerScore;
        }

        boolean isJaroWinklerScoreSignificant = jaroWinklerScore > 0.85;

        // Return the highest score among methods
        if (isJaroWinklerScoreSignificant || isNameMatcherScoreSignificant) {
            double maxScore = 0.0;

            if (isJaroWinklerScoreSignificant) {
                maxScore = Math.max(maxScore, jaroWinklerScore);
            }

            if (isNameMatcherScoreSignificant) {
                maxScore = Math.max(maxScore, nameMatcherScore);
            }

            return maxScore;
        }

        // If both strings are longer than 3 characters, check substring match
        if (upperPlace1.length() > 3 && upperPlace2.length() > 3) {
            if (upperPlace1.contains(upperPlace2) || upperPlace2.contains(upperPlace1)) {
                return 0.7;
            }
        }

        // No match found
        return null;
    }

    /**
     * Returns statistics about the city mapper.
     */
    public String getCityMapperStatistics() {
        return cityMapper.getStatistics();
    }

    protected Double matchIndividualWordsOfTheCity(String fullName1, String fullName2) {
        // Split by space and "-"
        List<String> names1 = splitAndFilterNames(fullName1);
        List<String> names2 = splitAndFilterNames(fullName2);

        if (names1.isEmpty() || names2.isEmpty()) {
            return null;
        }

        // Find matches between name parts
        NameMatchingResult result = findNameMatches(names1, names2);

        // Check if matching is valid according to the rules
        if (!isValidMatching(result, names1.size(), names2.size())) {
            return null;
        }

        // Calculate average probability
        return result.calculateAverageProbability();
    }

    /**
     * Splits names by space, "-", and after dots.
     * Filters out nobility particles.
     */
    private List<String> splitAndFilterNames(String fullName) {
        String[] parts = fullName.split("(?<=\\.)|[\\s-/]+");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .filter(part -> !NameUtils.isNobiliaryParticle(part))
                .collect(Collectors.toList());
    }

    /**
     * Finds matches between two lists of name parts.
     */
    private NameMatchingResult findNameMatches(List<String> names1, List<String> names2) {
        NameMatchingResult result = new NameMatchingResult();
        Set<Integer> used1 = new HashSet<>();
        Set<Integer> used2 = new HashSet<>();

        // Try to match names using different strategies
        matchWithJaroWinkler(names1, names2, used1, used2, result);
        matchAbbreviations(names1, names2, used1, used2, result);

        // Handle unmatched names
        handleUnmatchedNames(names1, names2, used1, used2, result);

        return result;
    }

    /**
     * Matches names using exact match, diacritical match, and Jaro-Winkler distance > 0.85.
     */
    private void matchWithJaroWinkler(List<String> names1, List<String> names2,
                                      Set<Integer> used1, Set<Integer> used2,
                                      NameMatchingResult result) {
        for (int i = 0; i < names1.size(); i++) {
            if (used1.contains(i)) continue;

            String name1 = names1.get(i);
            BestMatchResult bestMatch = findBestMatch(name1, names2, used2);

            if (bestMatch != null) {
                result.addMatch(bestMatch.score);
                used1.add(i);
                used2.add(bestMatch.index);
            }
        }
    }

    /**
     * Finds the best match for a given name among a list of candidates.
     */
    protected BestMatchResult findBestMatch(String name, List<String> candidates, Set<Integer> usedIndices) {
        // First pass: look for exact or diacritical matches
        for (int j = 0; j < candidates.size(); j++) {
            if (usedIndices.contains(j)) continue;

            String candidate = candidates.get(j);

            // Perfect match
            if (name.equals(candidate)) {
                return new BestMatchResult(j, 1.0);
            }

            // Diacritical match
            if (Utils.equalWithoutDiacriticals(name, candidate)) {
                return new BestMatchResult(j, 1.0);
            }
        }

        // Second pass: find best Jaro-Winkler match > 0.85
        int bestIndex = -1;
        double bestScore = 0.85; // Threshold

        for (int j = 0; j < candidates.size(); j++) {
            if (usedIndices.contains(j)) continue;

            double score = jaroWinkler.compare(name, candidates.get(j));
            if (score > bestScore) {
                bestScore = score;
                bestIndex = j;
            }
        }

        return bestIndex >= 0 ? new BestMatchResult(bestIndex, bestScore) : null;
    }

    /**
     * Result of finding the best match for a name.
     */
    protected static class BestMatchResult {
        public final int index;
        public final double score;

        public BestMatchResult(int index, double score) {
            this.index = index;
            this.score = score;
        }
    }

    /**
     * Matches names with middle initials (score 0.95).
     */
    private void matchAbbreviations(List<String> names1, List<String> names2,
                                    Set<Integer> used1, Set<Integer> used2,
                                    NameMatchingResult result) {
        for (int i = 0; i < names1.size(); i++) {
            if (used1.contains(i)) continue;

            for (int j = 0; j < names2.size(); j++) {
                if (used2.contains(j)) continue;

                if (isAbbreviationMatch(names1.get(i), names2.get(j))) {
                    result.addAbbreviationMatch(i, j, 0.95);
                    used1.add(i);
                    used2.add(j);
                    break;
                }
            }
        }
    }

    /**
     * Handles unmatched names according to the rules.
     */
    private void handleUnmatchedNames(List<String> names1, List<String> names2,
                                      Set<Integer> used1, Set<Integer> used2,
                                      NameMatchingResult result) {
        int unmatched1 = names1.size() - used1.size();
        int unmatched2 = names2.size() - used2.size();

        // If one side has more names, unmatched names from longer side get 0.8
        if (unmatched1 > 0 && unmatched2 == 0) {
            for (int i = 0; i < unmatched1; i++) {
                result.addMatch(0.8);
            }
        } else if (unmatched2 > 0 && unmatched1 == 0) {
            for (int i = 0; i < unmatched2; i++) {
                result.addMatch(0.8);
            }
        } else if (unmatched1 > 0 && unmatched2 > 0) {
            // Both sides have unmatched names
            result.setHasUnmatchableNames(true);
            int unmatchedCount = Math.max(unmatched1, unmatched2);
            for (int i = 0; i < unmatchedCount; i++) {
                result.addMatch(0.1);
            }
        }
    }

    /**
     * Checks if one name is a middle initial match for another.
     */
    private boolean isAbbreviationMatch(String name1, String name2) {
        String longer = name1.length() > name2.length() ? name1 : name2;
        String shorter = name1.length() <= name2.length() ? name1 : name2;

        if (shorter.length() == 2 && longer.length() == 2) {
            if (longer.endsWith(".")) {
                var stemp = shorter;
                shorter = longer;
                longer = stemp;
            }
        }

        return shorter.length() == 2 && shorter.endsWith(".") &&
                longer.startsWith(shorter.substring(0, 1));
    }

    /**
     * Validates if the matching result is acceptable according to the rules.
     */
    private boolean isValidMatching(NameMatchingResult result, int names1Size, int names2Size) {
        int minSize = Math.min(names1Size, names2Size);

        // All parts of the shorter name must have matches (include 0.8 and above)
        if (result.getMatchesAboveThreshold(0.8) < minSize) {
            return false;
        }

        // If both have more than 2 parts and there are unmatchable names,
        // there must be at least 2 real matches
        if (minSize > 2 && result.hasUnmatchableNames() && result.getRealMatches() < 2) {
            return false;
        }

        // In case of abbreviations, there must be one real match
        if (result.getMiddleInitialMatches() > 0 &&
                result.getRealMatches() - result.getMiddleInitialMatches() < 1) {
            return false;
        }

        // The first word (index 0) must not be an abbreviation match
        if (result.hasAbbreviationAtIndex(0)) {
            return false;
        }

        return true;
    }

    /**
     * Helper class to track matching results.
     */
    private static class NameMatchingResult {
        private final List<Double> scores = new ArrayList<>();
        private int middleInitialMatches = 0;
        private boolean hasUnmatchableNames = false;
        private final Set<Integer> abbreviationMatchIndices1 = new HashSet<>();
        private final Set<Integer> abbreviationMatchIndices2 = new HashSet<>();

        public void addMatch(double score) {
            scores.add(score);
        }

        public void addAbbreviationMatch(int index1, int index2, double score) {
            scores.add(score);
            middleInitialMatches++;
            abbreviationMatchIndices1.add(index1);
            abbreviationMatchIndices2.add(index2);
        }

        public boolean hasAbbreviationAtIndex(int index) {
            return abbreviationMatchIndices1.contains(index) || abbreviationMatchIndices2.contains(index);
        }

        public void setHasUnmatchableNames(boolean hasUnmatchableNames) {
            this.hasUnmatchableNames = hasUnmatchableNames;
        }

        public int getRealMatches() {
            return (int) scores.stream().filter(score -> score > 0.8).count();
        }

        public int getMatchesAboveThreshold(double threshold) {
            return (int) scores.stream().filter(score -> score >= threshold).count();
        }

        public int getMiddleInitialMatches() {
            return middleInitialMatches;
        }

        public boolean hasUnmatchableNames() {
            return hasUnmatchableNames;
        }

        public Double calculateAverageProbability() {
            if (scores.isEmpty()) {
                return null;
            }
            return scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }
}
