#!/bin/bash

# Script to remove quote delimiters from CSV file

INPUT_FILE="GermanCompanyNames.csv"
OUTPUT_FILE="GermanCompanyNames_clean.csv"

echo "Removing quote delimiters from $INPUT_FILE..."

# Remove leading and trailing double quotes from each line
sed 's/^"//; s/"$//' "$INPUT_FILE" > "$OUTPUT_FILE"

# Replace original file with cleaned version
mv "$OUTPUT_FILE" "$INPUT_FILE"

echo "Done! Quote delimiters removed from $INPUT_FILE"
