package com.complianx.matching;

import com.complianx.processing.LegalFormRetriever;
import com.complianx.util.names.CompanyNameUtil;
import com.complianx.utils.Utils;
import no.priv.garshol.duke.comparators.JaroWinkler;
import no.priv.garshol.duke.comparators.Levenshtein;

import java.util.*;

public class CorporateNameMatcher {

    private final JaroWinkler jaroWinkler;
    private final Levenshtein levenshtein;

    private static final LegalFormRetriever legalFormRetriever = new LegalFormRetriever();

    public CorporateNameMatcher() {
        this.jaroWinkler = new JaroWinkler();
        this.levenshtein = new Levenshtein();
    }

    public Double matchCorporateNames(String name1, String name2) {
        return matchCorporateNames(name1, name2, "DE", "DE");
    }

    /**
     * Matches two corporate names and returns the probability that they refer to the same entity.
     * Only performs matching if both sources provide non-empty names.
     *
     * @param name1 the first corporate name
     * @param name2 the second corporate name
     * @return probability between 0.0 and 1.0, or null if names don't match or are empty
     */
    public Double matchCorporateNames(String name1, String name2, String countryCode1, String countryCode2) {
        // Only perform if both sources provide a name
        if (name1 == null || name2 == null ||
                name1.trim().isEmpty() || name2.trim().isEmpty()) {
            return null;
        }

        String trimmedName1 = name1.trim().toUpperCase();
        String trimmedName2 = name2.trim().toUpperCase();

        // 1. If equals with equalWithoutDiacriticals(), return 1.0
        if (Utils.equalWithoutDiacriticals(trimmedName1, trimmedName2)) {
            return 1.0;
        }

        // 2. Check the Jaro-Winkler distance. If >= 0.85, return that value
        double jaroWinklerScore = jaroWinkler.compare(trimmedName1, trimmedName2);
        jaroWinklerScore = 1 - ((1-jaroWinklerScore) * 2.5); // this is a penalty, because JaroWinkler is too optimistic if the first letters match
        if (jaroWinklerScore >= 0.95) {
            return jaroWinklerScore;
        }

        double lScore =  trimmedName1.length()> 2 && trimmedName2.length() > 2 ? levenshtein.compare(trimmedName1, trimmedName2) : 0.0;
        if (lScore >= .95) {
            lScore = 0.95; // this is because we want some weight on differences
        }
        if (lScore >= 0.95) {
            return lScore;
        }

        // 3. Clean names from legal forms and brackets
        // just normally be provided

        String countryCode = countryCode1 == null ? null : countryCode1.toUpperCase();
        if (countryCode != null && countryCode2 != null && !countryCode.equals(countryCode2.toUpperCase())) {
            countryCode = null;
        }
        if (countryCode == null && countryCode2 != null) {
            countryCode = countryCode2.toUpperCase();
        }

        countryCode = countryCode == null ? "DE" : countryCode;

        String legalForm1 = legalFormRetriever.retrieveLegalForm(trimmedName1, countryCode);
        String legalForm2 = legalFormRetriever.retrieveLegalForm(trimmedName2, countryCode);

        String cleanedName1 = null;
        String cleanedName2 = null;

        //special case, one company name seems to be without legal form
        //then let us bring them together
        if (legalForm1 != null && legalForm2 == null) {
            legalForm2 = legalForm1;
        }
        if (legalForm2 != null && legalForm1 == null) {
            legalForm1 = legalForm2;
        }

        if (legalForm1 != null && legalForm1.equals(legalForm2)) {
            cleanedName1 = legalFormRetriever.companyNameWithoutLegalForm(trimmedName1, countryCode);
            cleanedName2 = legalFormRetriever.companyNameWithoutLegalForm(trimmedName2, countryCode);

            if (cleanedName1 != null ) {
                cleanedName1 = cleanedName1.toUpperCase();
            }

            if (cleanedName2 != null ) {
                cleanedName2 = cleanedName2.toUpperCase();
            }

        } else {
            cleanedName1 = LegalFormCleaner.cleanCompanyName(trimmedName1);
            cleanedName2 = LegalFormCleaner.cleanCompanyName(trimmedName2);
        }

        double legalFormMismatchPenalty = 1.0;
        if (legalForm1 != null && legalForm2 != null && !legalForm1.equals(legalForm2)) {
            legalFormMismatchPenalty = 0.9;
        }



        // If cleaned names are empty, return null
        if (cleanedName1.isEmpty() || cleanedName2.isEmpty()) {
            return null;
        }

        // Check cleaned names with equalWithoutDiacriticals
        if (Utils.equalWithoutDiacriticals(cleanedName1, cleanedName2)) {
            return 0.99;
        }


        // 4. Check word-based abbreviation matching
        Double wordMatchScore = matchWordsWithAbbreviations(cleanedName1, cleanedName2);
        if (wordMatchScore != null && wordMatchScore > 0.85) {
            return wordMatchScore;
        }

        double wordMatchScoreL = CompanyNameUtil.compareByWords(cleanedName1, cleanedName2);

        // 5. Check initial letter matching
        Double initialMatchScore = matchInitialLetters(cleanedName1, cleanedName2);
        if (initialMatchScore != null) {
            return initialMatchScore;
        }



        // Check cleaned names with Jaro-Winkler
        double cleanedJWScore = jaroWinkler.compare(cleanedName1, cleanedName2);
        cleanedJWScore = (1 - (1 - cleanedJWScore) * 2.5); // penalty for JaroWinkler being too optimistic if the first letters match
        double cleanedLScore = cleanedName1.length() > 2 && cleanedName2.length() > 2 ? levenshtein.compare(cleanedName1, cleanedName2) : 0.0;
        if (cleanedLScore >= .95) {
            cleanedLScore = 0.95; // this is because it is tokenized and the order of words have no meaning at all
        }
        if (cleanedJWScore * legalFormMismatchPenalty >= 0.9 && cleanedLScore >= wordMatchScoreL) {
            return cleanedJWScore * legalFormMismatchPenalty;
        }

        if (wordMatchScoreL > lScore && wordMatchScoreL > cleanedLScore && wordMatchScoreL >= 0.85) {
            return wordMatchScoreL * legalFormMismatchPenalty;
        }

        if (lScore >= 0.85) {
            return lScore * legalFormMismatchPenalty;
        }

        if (cleanedLScore >= 0.85) {
            return cleanedLScore * legalFormMismatchPenalty;
        }


        // Otherwise return null
        return null;
    }

    /**
     * Match words with abbreviation logic.
     * Split into words and check if they match in order with JW >= 0.85 or abbreviation logic.
     */
    private Double matchWordsWithAbbreviations(String name1, String name2) {
        List<String> words1 = splitIntoWords(name1);
        List<String> words2 = splitIntoWords(name2);

        if (words1.isEmpty() || words2.isEmpty()) {
            return null;
        }

        if (words1.size() == 1 || words2.size() == 1) {
            return null;
        }



        // Try matching in both directions
        Double score1 = matchWordSequence(words1, words2);
        Double score2 = matchWordSequence(words2, words1);

        // Return the higher score
        if (score1 != null && score2 != null) {
            return Math.max(score1, score2);
        } else if (score1 != null) {
            return score1;
        } else {
            return score2;
        }
    }

    /**
     * Split a name into words, handling spaces, hyphens, and dots.
     */
    private List<String> splitIntoWords(String name) {
        List<String> words = new ArrayList<>();

        // Split on spaces and hyphens first
        String[] parts = name.split("[\\s\\-]+");

        for (String part : parts) {
            if (part.trim().isEmpty()) {
                continue;
            }

            // Further split on dots, but preserve the dot with each word
            if (part.contains(".")) {
                String[] dotParts = part.split("(?<=\\.)");
                for (String dotPart : dotParts) {
                    if (!dotPart.trim().isEmpty()) {
                        words.add(dotPart.trim());
                    }
                }
            } else {
                words.add(part.trim());
            }
        }

        return words;
    }

    /**
     * Match two word sequences checking if they match in order.
     */
    private Double matchWordSequence(List<String> words1, List<String> words2) {
        int matches = 0;
        int totalWords = Math.min(words1.size(), words2.size());
        int maxTotalWords = Math.max(words1.size(), words2.size());

        if (totalWords <= (maxTotalWords/2.0)) {
            return null;
        }

        if (totalWords == 0) {
            return null;
        }

        double totalScore = 0.0;

        for (int i = 0; i < totalWords; i++) {
            String word1 = words1.get(i);
            String word2 = words2.get(i);

            // Check for exact match or diacritical match
            if (word1.equalsIgnoreCase(word2) || Utils.equalWithoutDiacriticals(word1, word2)) {
                matches++;
                totalScore += 1.0;
                continue;
            }

            // Check abbreviation matching
            if (isAbbreviation(word1, word2) || isAbbreviation(word2, word1)) {
                matches++;
                totalScore += 0.9;
                continue;
            }

            // Check Jaro-Winkler >= 0.85
            double wordJW = jaroWinkler.compare(word1, word2);
            if (wordJW >= 0.95) {
                matches++;
                totalScore += wordJW;
                continue;
            }



            // If we can't match this word, this sequence doesn't work
            return null;
        }

        // All words in the shorter sequence must match
        if (matches == totalWords) {
            return totalScore / (totalWords + (maxTotalWords - totalWords) * 0.2 );
        }

        return null;
    }

    /**
     * Check if one word is an abbreviation of another.
     * Examples: "Business" and "B." or "Machines" and "M."
     */
    private boolean isAbbreviation(String fullWord, String abbrev) {
        if (fullWord == null || abbrev == null || fullWord.length() <= abbrev.length()) {
            return false;
        }

        // Check if abbrev is a single letter followed by a dot
        if (abbrev.length() == 2 && abbrev.endsWith(".")) {
            char abbrevChar = Character.toUpperCase(abbrev.charAt(0));
            char firstChar = Character.toUpperCase(fullWord.charAt(0));
            return abbrevChar == firstChar;
        }

        // Check if abbrev is just the first letter(s) of fullWord
        if (abbrev.length() == 1) {
            char abbrevChar = Character.toUpperCase(abbrev.charAt(0));
            char firstChar = Character.toUpperCase(fullWord.charAt(0));
            return abbrevChar == firstChar;
        }

        return false;
    }

    /**
     * Check if one name is just the starting letters of the other.
     * Example: "International Business Machines" vs "IBM" -> 0.5
     */
    private Double matchInitialLetters(String name1, String name2) {
        List<String> words1 = splitIntoWords(name1);
        List<String> words2 = splitIntoWords(name2);

        // Try both directions
        if (matchesInitials(words1, name2)) {
            return 0.5;
        }
        if (matchesInitials(words2, name1)) {
            return 0.5;
        }

        return null;
    }

    /**
     * Check if a short name matches the initial letters of a longer name.
     */
    private boolean matchesInitials(List<String> longWords, String shortName) {
        if (longWords.isEmpty() || shortName.isEmpty()) {
            return false;
        }

        // Extract first letters from long words
        StringBuilder initials = new StringBuilder();
        for (String word : longWords) {
            if (!word.isEmpty()) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }

        String initialString = initials.toString();
        String upperShortName = shortName.toUpperCase().replaceAll("[^A-Z]", "");

        return initialString.equals(upperShortName);
    }
}
