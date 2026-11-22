#!/usr/bin/env bash
set -e

# Test syntax and help using bash interpreter
bash ./instalogger --help | grep -q "Usage: ./instalogger"
echo "CLI syntax test passed."
