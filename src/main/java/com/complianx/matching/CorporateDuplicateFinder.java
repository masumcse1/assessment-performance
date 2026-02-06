package com.complianx.matching;

import com.complianx.util.names.CompanyNameUtil;

import java.util.ArrayList;
import java.util.List;

public class CorporateDuplicateFinder {

    public CorporateNameMatcher corporateNameMatcher = new CorporateNameMatcher();


    public List<MatchResult> findDuplicates(List<String> companyNames) {
        List<MatchResult> matches = new ArrayList<>();

        // Compare each company name with every other company name
        for (int i = 0; i < companyNames.size(); i++) {
            for (int j = i + 1; j < companyNames.size(); j++) {
                String name1 = companyNames.get(i);
                String name2 = companyNames.get(j);

                Double score = corporateNameMatcher.matchCorporateNames(name1, name2);

                if (score != null && score > 0.85) {
                    matches.add(new MatchResult(name1, name2, score));
                }
            }
        }

        return matches;
    }

    /**
     * Helper class to store match results.
     */
    public static class MatchResult {
        public final String name1;
        public final String name2;
        public final double score;

        MatchResult(String name1, String name2, double score) {
            this.name1 = name1;
            this.name2 = name2;
            this.score = score;
        }
    }
}
