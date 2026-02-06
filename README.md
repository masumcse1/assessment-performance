# Assessment Performance - Developer Candidate Exercise

## Project Overview

This project implements a **corporate name duplicate detection system** that identifies when two company names likely refer to the same real-world entity. The matching algorithm is sophisticated, combining multiple string similarity techniques:

- Jaro-Winkler and Levenshtein distance calculations
- Legal form detection and removal (GmbH, AG, Ltd, Inc, etc.)
- Diacritic-insensitive comparisons (handling umlauts and accented characters)
- Abbreviation detection (e.g., "IBM" matches "International Business Machines")
- Multi-pass word-level matching

The system is designed to work with company names from various countries, with primary support for German legal forms.

---

## Rules

### Working on This Task

- **This task must be solved independently.** You may not receive help from another person.
- **AI and AI-powered coding tools are strongly encouraged.** Feel free to use tools like GitHub Copilot, ChatGPT, Claude Code, Antigravity or similar assistants to help you solve this task.

### Allowed Libraries and Tools

- **Any open-source Java library is allowed.** You may add dependencies to the `pom.xml` as needed.
- **Non-Java libraries are not allowed.** The solution must be pure Java.
- **Standalone servers (e.g., databases, search engines) are allowed but not advised.** If you choose to use external services, they should be integrated via [Testcontainers](https://www.testcontainers.org/) to ensure the solution remains self-contained and testable.

---

## Project Structure

```
src/
├── main/java/com/complianx/
│   ├── matching/
│   │   ├── CorporateDuplicateFinder.java   # <-- FOCUS OF THIS ASSESSMENT
│   │   ├── CorporateNameMatcher.java       # Core matching algorithm (DO NOT CHANGE)
│   │   ├── LegalFormCleaner.java           # Removes legal suffixes from names
│   │   ├── CityMatcher.java                # City name matching utilities
│   │   └── CityMapper.java                 # City name mapping
│   ├── processing/
│   │   └── LegalFormRetriever.java         # Legal form extraction logic
│   └── util/names/
│       ├── CompanyNameUtil.java            # Word-level comparison utilities
│       └── NameUtils.java                  # Name parsing utilities
│
└── test/
    ├── java/com/complianx/hr/xoev/matching/
    │   └── CompanyNamesCrossMatchTest.java  # Test class (two test methods)
    └── resources/companyNames/
        ├── companyNamesSmall.csv            # ~500 company names
        └── companyNamesLarge.csv            # ~244,000 company names
```

---

## The Problem: Performance

### Class Under Analysis: `CorporateDuplicateFinder`

Located at: [`src/main/java/com/complianx/matching/CorporateDuplicateFinder.java`](src/main/java/com/complianx/matching/CorporateDuplicateFinder.java)

The `findDuplicates()` method (lines 13-31) contains the core performance issue:

```java
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
```

### Why This Is Problematic

The nested loop creates **O(n²) time complexity**, where `n` is the number of company names. For each pair of names, the algorithm calls `matchCorporateNames()`, which performs multiple expensive string operations.

| Dataset | Companies | Comparisons | Feasibility |
|---------|-----------|-------------|-------------|
| Small   | ~500      | ~125,000    | Completes in seconds |
| Large   | ~244,000  | ~29.8 billion | **Will not complete** |

The matching algorithm itself (`CorporateNameMatcher.matchCorporateNames()`) is complex and computationally expensive per call. With nearly 30 billion comparisons needed for the large dataset, the current implementation is simply not viable.

---

## A Note on False Positives

The current algorithm produces one known **false positive** in the small dataset:

```
American Electric Power  ↔  American Tower  |  Score: 0.8550
```

These are two different companies, but the algorithm matches them due to word-level matching logic that tolerates "missing words." This tolerance exists intentionally to catch real duplicates where one name is a shortened form of another.

**Your solution does not need to preserve this specific false positive.** The word-matching edge cases are complex, and it is acceptable if your optimization does not reproduce this particular match.

---

## Your Task

1. **Optimize the duplicate detection** in `CorporateDuplicateFinder` so that it can handle the large dataset (`companyNamesLarge.csv`) in a reasonable time frame.

2. **Do NOT semantically change the matching algorithm.** The logic in `CorporateNameMatcher` and supporting classes must remain functionally identical. You may refactor or optimize these classes, but the matching behavior and scoring must produce the same results.


---

## Running the Tests

### Prerequisites
- Java 21 or later
- Maven 3.x

### Execute the Tests

```bash
# Run all tests
mvn test

# Run only the small dataset test
mvn test -Dtest=CompanyNamesCrossMatchTest#testSmallDataset

# Run only the large dataset test
mvn test -Dtest=CompanyNamesCrossMatchTest#testLargeDataset
```

### Test Methods

The test class [`CompanyNamesCrossMatchTest`](src/test/java/com/complianx/hr/xoev/matching/CompanyNamesCrossMatchTest.java) contains two test methods:

1. **`testSmallDataset()`** - Tests against `companyNamesSmall.csv` (~500 names)
   - Verifies that required duplicate pairs are found
   - Ensures no unexpected matches appear

2. **`testLargeDataset()`** - Tests against `companyNamesLarge.csv` (~244,000 names)
   - Prints output for manual verification
   - No assertions (performance validation)

**Note:** With the current implementation, `testLargeDataset()` will effectively hang or take an impractical amount of time to complete.

---

## Validation Criteria

Your solution is valid if:

| Criterion | Required |
|-----------|----------|
| Small dataset test passes (`testSmallDataset`) | Yes |
| Large dataset completes in reasonable time | Yes |
| Large dataset finds matches (not zero results) | Yes |
| Matching algorithm semantics unchanged | Yes |

**Important:** The large dataset contains real duplicates. If your solution returns zero matches for the large file, it is not correct.

---

## Constraints

- The matching algorithm in `CorporateNameMatcher` must remain semantically unchanged
- The small dataset test should pass (If your solution has problems to detect some of the patterns, like missing words, that can still be acceptable)
- The solution must work for both the small and large datasets
- Parallel processing is allowed to improve performance if needed. Assume this to run on standard hardware with 8 parallel cores
- Using GPUs is allowed, but we have so far no experience with it. Try to stick to CUDA or generic Java features

---

## Key Files Reference

| File | Purpose |
|------|---------|
| [`CorporateDuplicateFinder.java`](src/main/java/com/complianx/matching/CorporateDuplicateFinder.java) | **Optimize this class** |
| [`CorporateNameMatcher.java`](src/main/java/com/complianx/matching/CorporateNameMatcher.java) | Core matching logic (do not change behavior) |
| [`CompanyNamesCrossMatchTest.java`](src/test/java/com/complianx/hr/xoev/matching/CompanyNamesCrossMatchTest.java) | Test class to run |
| [`companyNamesSmall.csv`](src/test/resources/companyNames/companyNamesSmall.csv) | Small test dataset (~500 names) |
| [`companyNamesLarge.csv`](src/test/resources/companyNames/companyNamesLarge.csv) | Large test dataset (~244,000 names) |
