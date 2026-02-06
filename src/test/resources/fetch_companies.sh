#!/bin/bash

# Script to fetch German company names from Mockaroo API 1000 times

URL="https://api.mockaroo.com/api/3c5cbdf0?count=1000&key=2c0462a0"
OUTPUT_FILE="GermanCompanyNames.csv"

echo "Starting to fetch data from Mockaroo API..."

for i in $(seq 1 1000); do
    curl -s "$URL" >> "$OUTPUT_FILE"
    echo "Completed request $i of 1000"
done

echo "Done! Data saved to $OUTPUT_FILE"
