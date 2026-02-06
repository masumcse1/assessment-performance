package com.complianx.hr.xoev.matching;

import static org.assertj.core.api.Assertions.*;

import com.complianx.matching.CorporateDuplicateFinder;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tests for the CorporateDuplicateFinder.
 *
 * The small dataset test validates that known duplicates are found.
 * The large dataset test is for performance validation.
 */
class CompanyNamesCrossMatchTest {

    // Known matches that MUST be found by any valid solution
    private static final Set<String> REQUIRED_MATCHES = Set.of(
        "Warner Brothers Discovery|Warner Bros. Discovery",
        "Johnson & Johnson|Johnson and Jonson",
        "PNC Financial Services Group|PNC Financial Service G.",
        "PayPal Holdings|PayPal Holding"
    );

    // Known false positive that is acceptable but not required
    private static final Set<String> OPTIONAL_MATCHES = Set.of(
        "American Electric Power|American Tower"
    );

    @Test
    void testSmallDataset() throws IOException {
        List<String> companyNames = loadCompanyNamesFromCsv("companyNames/companyNamesSmall.csv");

        CorporateDuplicateFinder duplicateFinder = new CorporateDuplicateFinder();
        List<CorporateDuplicateFinder.MatchResult> matches = duplicateFinder.findDuplicates(companyNames);

        printMatches(matches, "Small Dataset");

        // Verify all required matches are found
        for (String requiredMatch : REQUIRED_MATCHES) {
            String[] parts = requiredMatch.split("\\|");
            boolean found = matches.stream().anyMatch(m ->
                (m.name1.equals(parts[0]) && m.name2.equals(parts[1])) ||
                (m.name1.equals(parts[1]) && m.name2.equals(parts[0]))
            );
            assertThat(found)
                .withFailMessage("Required match not found: %s <-> %s", parts[0], parts[1])
                .isTrue();
        }

        // Verify no unexpected matches (only required + optional are allowed)
        Set<String> allowedMatches = new java.util.HashSet<>(REQUIRED_MATCHES);
        allowedMatches.addAll(OPTIONAL_MATCHES);

        for (var match : matches) {
            String matchKey1 = match.name1 + "|" + match.name2;
            String matchKey2 = match.name2 + "|" + match.name1;
            boolean isAllowed = allowedMatches.contains(matchKey1) || allowedMatches.contains(matchKey2);
            assertThat(isAllowed)
                .withFailMessage("Unexpected match found: %s <-> %s (score: %.4f)",
                    match.name1, match.name2, match.score)
                .isTrue();
        }

        System.out.println("\nSmall dataset test PASSED - all required matches found, no unexpected matches.");
    }

    @Test
    void testLargeDataset() throws IOException {
        List<String> companyNames = loadCompanyNamesFromCsv("companyNames/companyNamesLarge.csv");

        CorporateDuplicateFinder duplicateFinder = new CorporateDuplicateFinder();
        List<CorporateDuplicateFinder.MatchResult> matches = duplicateFinder.findDuplicates(companyNames);

        printMatches(matches, "Large Dataset");

        // No assertions - just output for manual verification
        System.out.println("\nLarge dataset test completed. Found " + matches.size() + " matches.");
    }

    private void printMatches(List<CorporateDuplicateFinder.MatchResult> matches, String datasetName) {
        if (!matches.isEmpty()) {
            System.out.println("\n" + datasetName + ": Found " + matches.size() + " company name pairs matching above 0.85:");
            System.out.println("=".repeat(80));
            for (var match : matches) {
                System.out.printf("%-35s | %-35s | Score: %.4f%n",
                    match.name1, match.name2, match.score);
            }
            System.out.println("=".repeat(80));
        } else {
            System.out.println("\n" + datasetName + ": No matches found.");
        }
    }

    /**
     * Load company names from a CSV file using ClassLoader.
     */
    private List<String> loadCompanyNamesFromCsv(String resourcePath) throws IOException {
        List<String> names = new ArrayList<>();

        InputStream inputStream = getClass().getClassLoader()
            .getResourceAsStream(resourcePath);

        assertThat(inputStream)
            .withFailMessage("Could not find %s in resources", resourcePath)
            .isNotNull();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmedLine = line.trim();
                if (!trimmedLine.isEmpty()) {
                    names.add(trimmedLine);
                }
            }
        }

        assertThat(names)
            .withFailMessage("Expected to load company names from %s, but list is empty", resourcePath)
            .isNotEmpty();

        System.out.println("Loaded " + names.size() + " company names from " + resourcePath);

        return names;
    }
}
