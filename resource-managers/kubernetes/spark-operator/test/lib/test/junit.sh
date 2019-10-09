#!/bin/bash
# This utility file contains functions that format test output to be parsed into jUnit XML

# os::test::junit::declare_suite_start prints a message declaring the start of a test suite
# Any number of suites can be in flight at any time, so there is no failure condition for this
# script based on the number of suites in flight.
#
# Globals:
#  - JUNIT_REPORT_OUTPUT
#  - NUM_OS_JUNIT_SUITES_IN_FLIGHT
# Arguments:
#  - 1: the suite name that is starting
# Returns:
#  - increment NUM_OS_JUNIT_SUITES_IN_FLIGHT
function os::test::junit::declare_suite_start() {
    local suite_name=$1
    local num_suites=${NUM_OS_JUNIT_SUITES_IN_FLIGHT:-0}

    echo "=== BEGIN TEST SUITE github.com/openshift/origin/test/${suite_name} ===" >> "${JUNIT_REPORT_OUTPUT:-/dev/null}"
    NUM_OS_JUNIT_SUITES_IN_FLIGHT=$(( ${num_suites} + 1 ))
    export NUM_OS_JUNIT_SUITES_IN_FLIGHT
}
readonly -f os::test::junit::declare_suite_start

# os::test::junit::declare_suite_end prints a message declaring the end of a test suite
# If there aren't any suites in flight, this function will fail.
#
# Globals:
#  - JUNIT_REPORT_OUTPUT
#  - NUM_OS_JUNIT_SUITES_IN_FLIGHT
# Arguments:
#  - 1: the suite name that is starting
# Returns:
#  - export/decrement NUM_OS_JUNIT_SUITES_IN_FLIGHT
function os::test::junit::declare_suite_end() {
    local num_suites=${NUM_OS_JUNIT_SUITES_IN_FLIGHT:-0}
    if [[ "${num_suites}" -lt "1" ]]; then
        # we can't end a suite if none have been started yet
        echo "[ERROR] jUnit suite marker could not be placed, expected suites in flight, got ${num_suites}"
        return 1
    fi

    echo "=== END TEST SUITE ===" >> "${JUNIT_REPORT_OUTPUT:-/dev/null}"
    NUM_OS_JUNIT_SUITES_IN_FLIGHT=$(( ${num_suites} - 1 ))
    export NUM_OS_JUNIT_SUITES_IN_FLIGHT
}
readonly -f os::test::junit::declare_suite_end

# os::test::junit::declare_test_start prints a message declaring the start of a test case
# If there is already a test marked as being in flight, this function will fail.
#
# Globals:
#  - JUNIT_REPORT_OUTPUT
#  - NUM_OS_JUNIT_TESTS_IN_FLIGHT
# Arguments:
#  None
# Returns:
#  - increment NUM_OS_JUNIT_TESTS_IN_FLIGHT
function os::test::junit::declare_test_start() {
    local num_tests=${NUM_OS_JUNIT_TESTS_IN_FLIGHT:-0}
    if [[ "${num_tests}" -ne "0" ]]; then
        # someone's declaring the starting of a test when a test is already in flight
        echo "[ERROR] jUnit test marker could not be placed, expected no tests in flight, got ${num_tests}"
        return 1
    fi

    local num_suites=${NUM_OS_JUNIT_SUITES_IN_FLIGHT:-0}
    if [[ "${num_suites}" -lt "1" ]]; then
        # we can't end a test if no suites are in flight
        echo "[ERROR] jUnit test marker could not be placed, expected suites in flight, got ${num_suites}"
        return 1
    fi

    echo "=== BEGIN TEST CASE ===" >> "${JUNIT_REPORT_OUTPUT:-/dev/null}"
    NUM_OS_JUNIT_TESTS_IN_FLIGHT=$(( ${num_tests} + 1 ))
    export NUM_OS_JUNIT_TESTS_IN_FLIGHT
}
readonly -f os::test::junit::declare_test_start

# os::test::junit::declare_test_end prints a message declaring the end of a test case
# If there is no test marked as being in flight, this function will fail.
#
# Globals:
#  - JUNIT_REPORT_OUTPUT
#  - NUM_OS_JUNIT_TESTS_IN_FLIGHT
# Arguments:
#  None
# Returns:
#  - decrement NUM_OS_JUNIT_TESTS_IN_FLIGHT
function os::test::junit::declare_test_end() {
    local num_tests=${NUM_OS_JUNIT_TESTS_IN_FLIGHT:-0}
    if [[ "${num_tests}" -ne "1" ]]; then
        # someone's declaring the end of a test when a test is not in flight
        echo "[ERROR] jUnit test marker could not be placed, expected one test in flight, got ${num_tests}"
        return 1
    fi

    echo "=== END TEST CASE ===" >> "${JUNIT_REPORT_OUTPUT:-/dev/null}"
    NUM_OS_JUNIT_TESTS_IN_FLIGHT=$(( ${num_tests} - 1 ))
    export NUM_OS_JUNIT_TESTS_IN_FLIGHT
}
readonly -f os::test::junit::declare_test_end

# os::test::junit::check_test_counters checks that we do not have any test suites or test cases in flight
# This function should be called at the very end of any test script using jUnit markers to make sure no error in
# marking has occurred.
#
# Globals:
#  - NUM_OS_JUNIT_SUITES_IN_FLIGHT
#  - NUM_OS_JUNIT_TESTS_IN_FLIGHT
# Arguments:
#  None
# Returns:
#  None
function os::test::junit::check_test_counters() {
    if [[ "${NUM_OS_JUNIT_SUITES_IN_FLIGHT-}" -ne "0" ]]; then
        echo "[ERROR] Expected no test suites to be marked as in-flight at the end of testing, got ${NUM_OS_JUNIT_SUITES_IN_FLIGHT-}"
        return 1
    elif [[ "${NUM_OS_JUNIT_TESTS_IN_FLIGHT-}" -ne "0" ]]; then
        echo "[ERROR] Expected no test cases to be marked as in-flight at the end of testing, got ${NUM_OS_JUNIT_TESTS_IN_FLIGHT-}"
        return 1
    fi
}
readonly -f os::test::junit::check_test_counters

# os::test::junit::reconcile_output appends the necessary suite and test end statements to the jUnit output file
# in order to ensure that the file is in a consistent state to allow for parsing
#
# Globals:
#  - NUM_OS_JUNIT_SUITES_IN_FLIGHT
#  - NUM_OS_JUNIT_TESTS_IN_FLIGHT
# Arguments:
#  None
# Returns:
#  None
function os::test::junit::reconcile_output() {
    if [[ "${NUM_OS_JUNIT_TESTS_IN_FLIGHT:-0}" = "1" ]]; then
        os::test::junit::declare_test_end
    fi

    for (( i = 0; i < ${NUM_OS_JUNIT_SUITES_IN_FLIGHT:-0}; i++ )); do
        os::test::junit::declare_suite_end
    done
}
readonly -f os::test::junit::reconcile_output

# os::test::junit::generate_oscmd_report generats an XML jUnit report
# for the `os::cmd` suite from the raw test output. This function should
# be trapped on EXIT.
#
# Globals:
#  - JUNIT_REPORT_OUTPUT
#  - ARTIFACT_DIR
# Arguments:
#  None
# Returns:
#  None
function os::test::junit::generate_oscmd_report() {
    if [[ -z "${JUNIT_REPORT_OUTPUT:-}" ||
          -n "${JUNIT_REPORT_OUTPUT:-}" && ! -s "${JUNIT_REPORT_OUTPUT:-}" ]]; then
          # we can't generate a report
          return
    fi

    # get the jUnit output file into a workable state in case we
    # crashed in the middle of testing something
    os::test::junit::reconcile_output

    # check that we didn't mangle jUnit output
    os::test::junit::check_test_counters

    # use the junitreport tool to generate us a report
    os::util::ensure::built_binary_exists 'junitreport'

    junitreport --type oscmd                          \
                --suites nested                       \
                --roots github.com/openshift/origin   \
                --output "${ARTIFACT_DIR}/report.xml" \
                <"${JUNIT_REPORT_OUTPUT}"
    junitreport summarize <"${ARTIFACT_DIR}/report.xml"
}