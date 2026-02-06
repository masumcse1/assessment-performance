#!/bin/bash

# Script to remove all header lines (companyName) from CSV file

INPUT_FILE="GermanCompanyNames.csv"
OUTPUT_FILE="GermanCompanyNames_clean.csv"

echo "Removing all 'companyName' header lines from $INPUT_FILE..."

# Remove all lines that exactly match "companyName"
grep -v "^companyName$" "$INPUT_FILE" > "$OUTPUT_FILE"

# Replace original file with cleaned version
mv "$OUTPUT_FILE" "$INPUT_FILE"

echo "Done! All header lines removed from $INPUT_FILE"
