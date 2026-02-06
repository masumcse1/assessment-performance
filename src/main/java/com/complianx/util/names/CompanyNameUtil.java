package com.complianx.util.names;

import com.complianx.utils.Utils;
import no.priv.garshol.duke.comparators.JaroWinkler;
import no.priv.garshol.duke.comparators.Levenshtein;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class for comparing company name words using JaroWinkler similarity
 * with optional prefix matching for abbreviated company names.
 */
public class CompanyNameUtil {

    private static final JaroWinkler jaroWinkler = new JaroWinkler();
    private static final Levenshtein levenshtein = new Levenshtein();
    private static final double SCORE_THRESHOLD = 0.6;
    private static final double LENGTH_DIFFERENCE_THRESHOLD = 0.2; // 20%
    private static final String SPECIAL_CHARS_REGEX = "[.;:/$%&\"!]+$";

    /**
     * Result of word comparison containing the score and whether prefix check was used.
     *
     * @param score           the similarity score (0.0-1.0), or 0.0 if below threshold
     * @param usedPrefixCheck true if prefix matching was used, false otherwise
     */
    public record CompareResult(double score, boolean usedPrefixCheck) {
    }

    /**
     * Represents a match entry for a word from list A to a word in list B.
     *
     * @param indexB     the index in list B that matched (null if no match found)
     * @param score      the similarity score of the match
     * @param usedPrefix true if prefix matching was used for this match
     */
    public record MatchEntry(Integer indexB, double score, boolean usedPrefix) {
    }

    /**
     * Result of matching two word lists.
     *
     * @param matches    list of match entries indexed by position in list A (can contain null entries)
     * @param unmatchedB list of indices from list B that were not matched to any word in A
     */
    public record MatchResult(List<MatchEntry> matches, List<Integer> unmatchedB) {
    }

    /**
     * Compares two words using JaroWinkler similarity with optional prefix matching.
     *
     * @param word1            first word to compare (can be null)
     * @param word2            second word to compare (can be null)
     * @param allowPrefixCheck if true, enables prefix matching for strings with 20%+ length difference
     * @return CompareResult with score (0.0 if below 0.6 threshold) and usedPrefixCheck flag
     */
    public static CompareResult compareWord(String word1, String word2, boolean allowPrefixCheck, boolean canUseAbbreviation) {
        // Handle null inputs
        if (word1 == null || word2 == null) {
            return new CompareResult(0.0, false);
        }

        // Handle empty strings
        if (word1.isEmpty() || word2.isEmpty()) {
            if (word1.isEmpty() && word2.isEmpty()) {
                return new CompareResult(1.0, false);
            }
            return new CompareResult(0.0, false);
        }

        // Calculate standard JaroWinkler score for complete words
        //double fullScore = jaroWinkler.compare(word1, word2);
        double fullScore;
        if (word1.length() <= 2 && word2.length() <= 2) {
            fullScore = word1.equals(word2) ? 1.0 : 0.0;
        } else {
            fullScore = Math.min(levenshtein.compare(word1, word2), jaroWinkler.compare(word1, word2));
        }

        double abbrScore = 0.0;
        if (allowPrefixCheck && canUseAbbreviation) {
            if (word1.length()>1 && word2.length()>1 && isLikelyAbbreviation(word1, word2)) {
                abbrScore = 0.95;
            }
        }


        // If prefix check is not allowed or words are same length, return full score
        if (!allowPrefixCheck || word1.length() == word2.length()) {
            return applyThreshold(fullScore, false);
        }

        // Determine longer and shorter strings
        String longer = word1.length() > word2.length() ? word1 : word2;
        String shorter = word1.length() <= word2.length() ? word1 : word2;

        // Check if length difference is at least 20%
        double lengthDifference = (double) (longer.length() - shorter.length()) / shorter.length();
        if (lengthDifference < LENGTH_DIFFERENCE_THRESHOLD) {
            return applyThreshold(fullScore, false);
        }

        // Remove special characters from the end of both strings
        String longerClean = longer.replaceAll(SPECIAL_CHARS_REGEX, "");
        String shorterClean = shorter.replaceAll(SPECIAL_CHARS_REGEX, "");

        // If cleaning resulted in empty strings, return full score
        if (longerClean.isEmpty() || shorterClean.isEmpty()) {
            return applyThreshold(fullScore, false);
        }

        // Extract prefix of longer string matching the length of shorter string
        String prefix = longerClean.length() >= shorterClean.length()
                ? longerClean.substring(0, shorterClean.length())
                : longerClean;

        // Compare prefix with shorter string
        // For short abbreviations (<=3 chars), use exact match
        // For longer abbreviations (>3 chars), use Levenshtein distance
        double prefixScore;
        if (shorterClean.length() <= 3) {
            prefixScore = prefix.equals(shorterClean) ? 1.0 : 0.0;
        } else {
            prefixScore = Math.min(levenshtein.compare(prefix, shorterClean), jaroWinkler.compare(prefix, shorterClean));
            char lastChar1 = prefix.charAt(prefix.length() - 1);
            char lastChar2 = shorterClean.charAt(shorterClean.length() - 1);
            if (lastChar1 != lastChar2 && prefix.length() != longerClean.length()) {
                // check if the last char is in the rest of the string
                String rest = longerClean.substring(prefix.length());
                if (!rest.contains(String.valueOf(lastChar2))) {
                    prefixScore = prefixScore * 0.8; // this is a penalty, that the last character is not in the rest of the word.
                }
            }

        }

        // Return the better score
        if (prefixScore > fullScore && prefixScore > abbrScore) {
            return applyThreshold(prefixScore, true);
        } else if (fullScore >= abbrScore ) {
            return applyThreshold(fullScore, false);
        } else {
            return applyThreshold(abbrScore, true);
        }

    }

    /**
     * Applies the threshold to the score: returns 0.0 if score is below 0.6.
     */
    private static CompareResult applyThreshold(double score, boolean usedPrefixCheck) {
        if (score < SCORE_THRESHOLD) {
            return new CompareResult(0.0, false);
        }
        return new CompareResult(score, usedPrefixCheck);
    }

    /**
     * Finds the best matching word from list B for a given word from list A.
     *
     * @param wordA               the word from list A to match
     * @param previousIndexB      the index of the previous word in list B (used to check word order) or -1 if none
     * @param listB               the list of words from B
     * @param unmatchedB          set of indices in list B that are still available for matching
     * @param allowPrefixMatching whether to allow prefix matching
     * @param minScore            minimum score threshold for accepting a match
     * @return MatchEntry with the best match, or null if no match meets the criteria
     */
    private static MatchEntry findBestMatch(String wordA,
                                            int previousIndexB,
                                            List<String> listB,
                                            Set<Integer> unmatchedB,
                                            boolean allowPrefixMatching,
                                            double minScore,
                                            boolean isInOrder) {
        if (wordA == null || listB == null || unmatchedB == null || unmatchedB.isEmpty()) {
            return null;
        }

        MatchEntry bestMatch = null;
        double bestScore = minScore - 0.0001; // Slightly below threshold to ensure >= comparison

        for (int indexB : unmatchedB) {
            if (indexB < 0 || indexB >= listB.size()) {
                continue;
            }

            String wordB = listB.get(indexB);
            CompareResult result = compareWord(wordA, wordB, allowPrefixMatching, isInOrder && indexB > previousIndexB);

            // Only consider matches that meet or exceed the minimum score
            if (result.score() >= minScore && result.score() > bestScore) {
                bestScore = result.score();
                bestMatch = new MatchEntry(indexB, result.score(), result.usedPrefixCheck());
            }
        }

        return bestMatch;
    }

    /**
     * Matches two lists of words using a multi-pass heuristic algorithm.
     * <p>
     * The algorithm performs 6 passes with progressively relaxed matching criteria:
     * 1. minScore=0.98, no prefix matching (exact/near-exact matches)
     * 2. minScore=0.98, with prefix matching (exact abbreviations)
     * 3. minScore=0.95, with prefix matching (very similar words)
     * 4. minScore=0.9, with prefix matching (similar words)
     * 5. minScore=0.8, with prefix matching (moderately similar)
     * 6. minScore=0.6, with prefix matching (minimum threshold)
     * <p>
     * Each word in list A is matched at most once, and each word in list B can only
     * be matched to one word in list A. The algorithm stops early if all words in A
     * are matched.
     *
     * @param listA the first list of words (source list)
     * @param listB the second list of words (target list)
     * @return MatchResult containing matches indexed by list A positions and unmatched B indices
     */
    public static MatchResult matchWordLists(List<String> listA, List<String> listB) {
        if (listA == null || listB == null) {
            return new MatchResult(
                    listA == null ? List.of() : new ArrayList<>(listA.size()),
                    listB == null ? List.of() : new ArrayList<>()
            );
        }

        // Initialize result structures
        List<MatchEntry> matches = new ArrayList<>(listA.size());
        for (int i = 0; i < listA.size(); i++) {
            matches.add(null);
        }

        // Initialize unmatchedB with all indices from list B
        Set<Integer> unmatchedB = new HashSet<>();
        for (int i = 0; i < listB.size(); i++) {
            unmatchedB.add(i);
        }

        boolean isInOrder = true;

        // Define the 6 matching passes
        MatchingPass[] passes = {
                new MatchingPass(0.98, false),  // Pass 1: Exact/near-exact, no prefix
                new MatchingPass(0.98, true),   // Pass 2: Exact/near-exact, with prefix
                new MatchingPass(0.95, true),   // Pass 3: Very similar
                new MatchingPass(0.9, true),    // Pass 4: Similar
                new MatchingPass(0.8, true),    // Pass 5: Moderately similar
                new MatchingPass(0.6, true)     // Pass 6: Minimum threshold
        };

        // Execute passes
        for (MatchingPass pass : passes) {
            boolean allMatched = true;

            for (int indexA = 0; indexA < listA.size(); indexA++) {
                // Skip if already matched
                if (matches.get(indexA) != null) {
                    continue;
                }

                allMatched = false;
                String wordA = listA.get(indexA);
                var previousIndex = previousIndex(matches, indexA);

                // Find best match for this word
                MatchEntry match = findBestMatch(wordA, previousIndex, listB, unmatchedB,
                        pass.allowPrefix, pass.minScore, isInOrder);

                if (match != null) {
                    matches.set(indexA, match);
                    unmatchedB.remove(match.indexB());
                    if (previousIndex(matches, indexA) > match.indexB()) {
                        isInOrder = false;
                    }
                }
            }

            // Early exit if all words from A are matched
            if (allMatched) {
                break;
            }
        }

        // Convert unmatchedB set to sorted list
        List<Integer> unmatchedList = new ArrayList<>(unmatchedB);
        unmatchedList.sort(Integer::compareTo);

        return new MatchResult(matches, unmatchedList);
    }

    /**
     * Helper class to define matching pass parameters.
     */
    private static class MatchingPass {
        final double minScore;
        final boolean allowPrefix;

        MatchingPass(double minScore, boolean allowPrefix) {
            this.minScore = minScore;
            this.allowPrefix = allowPrefix;
        }
    }

    /**
     * Calculates the number of word order mismatches in a list of match entries.
     * A mismatch occurs when a matched word appears before its predecessor in list B.
     *
     * @param matches list of match entries indexed by list A positions
     * @return count of word order mismatches
     */
    private static int calculateWordOrderMismatches(List<MatchEntry> matches) {
        if (matches == null || matches.size() <= 1) {
            return 0;
        }

        int penalty = 0;
        Integer previousIndexB = null;

        for (MatchEntry match : matches) {
            if (match == null) {
                continue; // Skip unmatched words
            }

            if (previousIndexB != null && match.indexB() < previousIndexB) {
                penalty++;
            }

            previousIndexB = match.indexB();
        }

        return penalty;
    }

    /**
     * Calculates the number of word order mismatches in a list of match entries.
     * A mismatch occurs when a matched word appears before its predecessor in list B.
     *
     * @param matches list of match entries indexed by list A positions
     * @return count of word order mismatches
     */
    private static int previousIndex(List<MatchEntry> matches, int endIndex) {
        if (matches == null || matches.size() <= 1) {
            return 0;
        }

        Integer previousIndexB = 0;

        for (int i = 0; i < endIndex; i++) {
            var match = matches.get(i);
            if (match == null) {
                continue; // Skip unmatched words
            }

            previousIndexB = match.indexB();
        }

        return previousIndexB;
    }

    /**
     * Compares two strings by splitting them into words and matching the words.
     * <p>
     * Algorithm:
     * 1. Splits both strings on space, hyphen, slash, and ampersand
     * 2. Normalizes words using diacritic removal
     * 3. Uses the shorter word list as the primary list
     * 4. Returns 0.0 if primary list size * 2 <= other list size (too much mismatch)
     * 5. Matches words using matchWordLists()
     * 6. Calculates penalties for:
     * - Less than 50% words matched (returns 0.0)
     * - Unmatched words in the longer list (beyond 1 free per 3 matched)
     * - Word order mismatches
     * 7. Returns final score with penalties applied
     *
     * @param a first string to compare
     * @param b second string to compare
     * @return similarity score between 0.0 and 1.0
     */
    public static double compareByWords(String a, String b) {
        // Handle null inputs
        if (a == null || b == null) {
            if (a == null && b == null) {
                return 1.0;
            }
            return 0.0;
        }

        // Handle empty strings
        if (a.trim().isEmpty() || b.trim().isEmpty()) {
            if (a.trim().isEmpty() && b.trim().isEmpty()) {
                return 1.0;
            }
            return 0.0;
        }

        // Step 1: Split words on space, hyphen, slash, and ampersand
        List<String> wordsA = splitAndCleanWords(a);
        List<String> wordsB = splitAndCleanWords(b);

        // Handle empty word lists after splitting
        if (wordsA.isEmpty() || wordsB.isEmpty()) {
            if (wordsA.isEmpty() && wordsB.isEmpty()) {
                return 1.0;
            }
            return 0.0;
        }

        // Step 2: Determine primary list (shorter one)
        List<String> listA;
        List<String> listB;
        if (wordsA.size() <= wordsB.size()) {
            listA = wordsA;
            listB = wordsB;
        } else {
            listA = wordsB;
            listB = wordsA;
        }

        // Step 3: Early exit if too much size mismatch
        if (listA.size() * 2 <= listB.size()) {
            return 0.0;
        }

        // Step 4: Normalize all words
        List<String> normalizedA = listA.stream()
                .map(Utils::NormalizeWithoutDiacriticalsPreserveSpecialCharacters)
                .collect(Collectors.toList());

        List<String> normalizedB = listB.stream()
                .map(Utils::NormalizeWithoutDiacriticalsPreserveSpecialCharacters)
                .collect(Collectors.toList());

        // Step 5: Call matchWordLists
        MatchResult matchResult = matchWordLists(normalizedA, normalizedB);

        // Step 6: Calculate word order mismatches
        int wordOrderMismatches = calculateWordOrderMismatches(matchResult.matches());

        // Step 7: Calculate final score
        return calculateFinalScore(matchResult, listA.size(), wordOrderMismatches, normalizedA, normalizedB);
    }

    /**
     * Splits a string into words on space, hyphen, slash, and ampersand, then trims and filters empty strings.
     */
    private static List<String> splitAndCleanWords(String input) {
        if (input == null) {
            return List.of();
        }
        input = input.replace("&", "");
        return Arrays.stream(input.split("[\\s\\-/]+"))
                .map(String::trim)
                .filter(word -> !word.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Calculates the final score based on match results and penalties.
     */
    private static double calculateFinalScore(MatchResult matchResult, int listASize, int wordOrderMismatches,
                                              List<String> listA, List<String> listB) {
        List<MatchEntry> matches = matchResult.matches();

        // Count matched words
        long matchedCount = matches.stream().filter(m -> m != null).count();

        // Check if less than 50% of A words matched
        // Need more than half: for 2 words need 2, for 3 words need 2, for 4 words need 3
        if (matchedCount < (listASize + 1) / 2) {
            return 0.0;
        }

        // Check if at least one word was matched without prefix matching
        boolean hasNonPrefixMatch = matches.stream()
                .filter(m -> m != null)
                .anyMatch(m -> !m.usedPrefix());

        if (!hasNonPrefixMatch) {
            return 0.0;
        }

        // Check character coverage: at least 50% of total characters must be matched
        // Total characters = sum of longer word lengths for all matches
        // Matched characters = sum of shorter word lengths for all matches
        int totalCharacters = 0;
        int matchedCharacters = 0;

        for (int i = 0; i < matches.size(); i++) {
            MatchEntry match = matches.get(i);
            if (match != null) {
                String wordA = listA.get(i);
                String wordB = listB.get(match.indexB());
                int longerLength = Math.max(wordA.length(), wordB.length());
                int shorterLength = Math.min(wordA.length(), wordB.length());

                totalCharacters += longerLength;
                matchedCharacters += shorterLength;
            }
        }

        // If matched characters < 50% of total, the match is too speculative
        if (totalCharacters > 0 && matchedCharacters < totalCharacters / 2.0) {
            return 0.0;
        }

        // Calculate average score of all words in A
        double sumScores = 0.0;
        for (MatchEntry match : matches) {
            if (match != null) {
                double scoreToAdd = match.score();
                // Apply penalty for prefix matches
                if (match.usedPrefix()) {
                    scoreToAdd -= 0.1;
                }
                sumScores += scoreToAdd;
            } else {
                sumScores += 0.4; // Synthetic score for unmatched A words
            }
        }
        double average = sumScores / listASize;

        // Apply penalty for unmatched B words
        int unmatchedBCount = matchResult.unmatchedB().size();
        int freeUnmatchedB = (int) matchedCount / 3; // One free for every 3 matched
        int penalizedUnmatchedB = Math.max(0, unmatchedBCount - freeUnmatchedB);

        for (int i = 0; i < penalizedUnmatchedB; i++) {
            average *= 0.95;
        }

        // Apply penalty for word order mismatches
        for (int i = 0; i < wordOrderMismatches; i++) {
            average *= 0.98;
        }

        return average;
    }

    /**
     * Checks if one string is likely an abbreviation of another by verifying that
     * characters from the shorter string appear in the longer string in the same order.
     *
     * <p>Algorithm:
     * <ul>
     *   <li>Automatically determines which string is shorter</li>
     *   <li>Returns false if strings are same length (not an abbreviation)</li>
     *   <li>Performs case-insensitive comparison</li>
     *   <li>First character of both strings must match</li>
     *   <li>Each subsequent character of the shorter string must appear in the longer string
     *       after the position of the previous match</li>
     * </ul>
     *
     * <p>Examples:
     * <ul>
     *   <li>"Deutschland", "Dtl" → true (D at 0, t at 4, l at 11)</li>
     *   <li>"Eintragung", "Etr" → true (E at 0, t at 3, r at 4)</li>
     *   <li>"Eintragung", "Egr" → false (g at 6, but r at 4 comes before g)</li>
     *   <li>"Technology", "Tech" → true (consecutive prefix)</li>
     * </ul>
     *
     * @param str1 first string to compare (can be null)
     * @param str2 second string to compare (can be null)
     * @return true if one string is likely an abbreviation of the other, false otherwise
     */
    public static boolean isLikelyAbbreviation(String str1, String str2) {
        // Handle null inputs
        if (str1 == null || str2 == null) {
            return false;
        }

        // Handle empty strings
        if (str1.isEmpty() || str2.isEmpty()) {
            return false;
        }

        // Determine which is shorter and which is longer
        String longer, shorter;
        if (str1.length() > str2.length()) {
            longer = str1;
            shorter = str2;
        } else if (str2.length() > str1.length()) {
            longer = str2;
            shorter = str1;
        } else {
            // Same length - not an abbreviation
            return false;
        }

        // Convert to uppercase for case-insensitive comparison
        longer = longer.toUpperCase();
        shorter = shorter.toUpperCase();

        // First character must match
        if (longer.charAt(0) != shorter.charAt(0)) {
            return false;
        }

        // Find all characters of shorter string in longer string in order
        int posInLonger = 0;
        for (int i = 0; i < shorter.length(); i++) {
            char c = shorter.charAt(i);

            // Find this character in longer string starting after current position
            int foundPos = longer.indexOf(c, posInLonger);

            if (foundPos == -1) {
                // Character not found in remaining part of longer string
                return false;
            }

            // Move position forward for next search (start after this match)
            posInLonger = foundPos + 1;
        }

        return true;
    }
}
