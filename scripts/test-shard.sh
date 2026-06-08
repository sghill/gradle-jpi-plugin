#!/bin/bash
# Test sharding script for :jpi2:test
# Splits tests across multiple parallel shards for faster feedback.
#
# Usage:
#   SHARD_INDEX=0 TOTAL_SHARDS=4 MAX_WORKERS=4 ./scripts/test-shard.sh
#   Or with args: ./scripts/test-shard.sh --shard-index 0 --total-shards 4 --max-workers 4
#
# Environment variables:
#   SHARD_INDEX      - Which shard to run (0-based)
#   TOTAL_SHARDS     - Total number of shards
#   MAX_WORKERS      - Max Gradle workers per shard
#   JPI2_MODULE      - Module path (default: jpi2)
#   TEST_FILTER      - Test filter pattern (default: *IntegrationTest)
#   COMBINE_RESULTS  - If "true", copies test results to combined directory
#   BUILD_DIR        - Gradle build directory (default: jpi2/build)
#
# Example CI pipeline:
#   for shard in {0..3}; do
#     SHARD_INDEX=$shard TOTAL_SHARDS=4 MAX_WORKERS=4 ./scripts/test-shard.sh &
#   done
#   wait

set -euo pipefail

# Parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            --shard-index)
                SHARD_INDEX="$2"
                shift 2
                ;;
            --total-shards)
                TOTAL_SHARDS="$2"
                shift 2
                ;;
            --max-workers)
                MAX_WORKERS="$2"
                shift 2
                ;;
            --jpi2-module)
                JPI2_MODULE="$2"
                shift 2
                ;;
            --test-filter)
                TEST_FILTER="$2"
                shift 2
                ;;
            --combine-results)
                COMBINE_RESULTS="$2"
                shift 2
                ;;
            --help)
                echo "Usage: $0 [options]"
                echo "Options:"
                echo "  --shard-index N    Which shard to run (0-based)"
                echo "  --total-shards N   Total number of shards"
                echo "  --max-workers N    Max Gradle workers per shard"
                echo "  --jpi2-module P    Module path (default: jpi2)"
                echo "  --test-filter F    Test filter pattern (default: *IntegrationTest)"
                echo "  --combine-results B Copy results to combined directory"
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                exit 1
                ;;
        esac
    done
}

# Default values
SHARD_INDEX="${SHARD_INDEX:-}"
TOTAL_SHARDS="${TOTAL_SHARDS:-}"
MAX_WORKERS="${MAX_WORKERS:-4}"
JPI2_MODULE="${JPI2_MODULE:-jpi2}"
TEST_FILTER="${TEST_FILTER:-*IntegrationTest}"
COMBINE_RESULTS="${COMBINE_RESULTS:-false}"
BUILD_DIR="${BUILD_DIR:-$JPI2_MODULE/build}"

# Parse command line args if provided
if [[ $# -gt 0 ]]; then
    parse_args "$@"
fi

# Validate required parameters
if [[ -z "$SHARD_INDEX" ]] || [[ -z "$TOTAL_SHARDS" ]]; then
    echo "Error: SHARD_INDEX and TOTAL_SHARDS must be set via environment or --shard-index/--total-shards"
    echo "Usage: SHARD_INDEX=0 TOTAL_SHARDS=4 $0 [--shard-index N --total-shards N]"
    exit 1
fi

# Validate shard index
if [[ "$SHARD_INDEX" -lt 0 ]] || [[ "$SHARD_INDEX" -ge "$TOTAL_SHARDS" ]]; then
    echo "Error: SHARD_INDEX must be between 0 and TOTAL_SHARDS-1"
    exit 1
fi

# Get test classes for the module
get_test_classes() {
    local module="$1"
    local filter="$2"
    find "$module/src/test/java" -name "${filter}.java" \
        | sed 's|.*/||' \
        | sed 's|\.java$||' \
        | sort
}

# Split array into chunks (modified in place)
# Usage: split_array "${array[@]}" num_chunks
split_array() {
    local -a input=("$@")
    local num_chunks="$TOTAL_SHARDS"
    local total=${#input[@]}
    local per_shard=$(( (total + num_chunks - 1) / num_chunks ))
    
    local start=$(( SHARD_INDEX * per_shard ))
    local end=$(( start + per_shard ))
    [[ $end -gt $total ]] && end=$total
    
    for (( i=start; i<end; i++ )); do
        echo "${input[$i]}"
    done
}

# Main execution
main() {
    local start_time=$(date +%s)
    
    echo "=========================================="
    echo "Test Shard $SHARD_INDEX of $TOTAL_SHARDS"
    echo "Max Workers: $MAX_WORKERS"
    echo "Module: $JPI2_MODULE"
    echo "Test Filter: $TEST_FILTER"
    echo "=========================================="
    
    # Get all test classes
    local -a all_tests=($(get_test_classes "$JPI2_MODULE" "$TEST_FILTER"))
    local total_tests=${#all_tests[@]}
    
    if [[ $total_tests -eq 0 ]]; then
        echo "Error: No tests found matching $TEST_FILTER in $JPI2_MODULE/src/test/java"
        exit 1
    fi
    
    # Calculate which tests this shard should run
    local -a shard_tests=()
    while IFS= read -r test; do
        shard_tests+=("$test")
    done < <(split_array "${all_tests[@]}")
    
    local shard_test_count=${#shard_tests[@]}
    echo "Total tests: $total_tests"
    echo "Tests in this shard: $shard_test_count"
    echo ""
    echo "Tests to run:"
    for test in "${shard_tests[@]}"; do
        echo "  - $test"
    done
    echo ""
    
    # Create working directory for this shard
    local shard_results_dir="$BUILD_DIR/test-results/shard-$SHARD_INDEX"
    local combined_results_dir="$BUILD_DIR/test-results/test"
    mkdir -p "$shard_results_dir"
    
    # Build Gradle command with --tests filters
    local -a gradle_cmd=(./gradlew)
    
    # Add max workers
    if [[ -n "$MAX_WORKERS" ]] && [[ "$MAX_WORKERS" -gt 0 ]]; then
        gradle_cmd+=("--max-workers=$MAX_WORKERS")
    fi
    
    gradle_cmd+=("${JPI2_MODULE}:test")
    gradle_cmd+=("--rerun-tasks")
    
    # Build test filter arguments
    for test in "${shard_tests[@]}"; do
        gradle_cmd+=("--tests" "org.jenkinsci.gradle.plugins.jpi2.$test")
    done
    
    echo "Running Gradle..."
    echo "Command: ${gradle_cmd[*]}"
    echo ""
    
    # Run tests with filtering
    if ! "${gradle_cmd[@]}" 2>&1; then
        echo "Tests failed"
        exit 1
    fi
    echo "Tests passed"
    
    # Copy results to shard directory for later aggregation
    if [[ -d "$combined_results_dir" ]]; then
        cp -r "$combined_results_dir"/* "$shard_results_dir/" 2>/dev/null || true
    fi
    
    local end_time=$(date +%s)
    local duration=$((end_time - start_time))
    
    echo ""
    echo "=========================================="
    echo "Shard $SHARD_INDEX completed in ${duration}s"
    echo "Results: $shard_results_dir"
    echo "=========================================="
    
    # Optionally combine results (for CI environments that need merged reports)
    if [[ "$COMBINE_RESULTS" == "true" ]]; then
        echo "Combining results..."
        local all_results_dir="$BUILD_DIR/test-results/all-shards"
        mkdir -p "$all_results_dir"
        
        for (( i=0; i<TOTAL_SHARDS; i++ )); do
            local src_dir="$BUILD_DIR/test-results/shard-$i"
            if [[ -d "$src_dir" ]]; then
                cp -r "$src_dir"/* "$all_results_dir/" 2>/dev/null || true
            fi
        done
        
        echo "Combined results: $all_results_dir"
    fi
}

main