package org.jenkinsci.gradle.plugins.jpi2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.engine.TestExecutionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A JUnit Platform TestExecutionListener that writes test execution events
 * to a JSON file in Buildkite Test Insights format.
 *
 * <p>The output is an array of test result objects as documented at:
 * <a href="https://buildkite.com/docs/test-engine/test-collection/importing-json">Buildkite Test Insights JSON Format</a>
 *
 * <p>This listener is automatically registered via JUnit Platform's service provider
 * mechanism. The output file can be overridden via the system property
 * {@code buildkite.test.insights.file} (defaults to {@code build/test-insights/test-insights.json}).
 */
public class BuildkiteTestExecutionListener implements TestExecutionListener {

    private static final String DEFAULT_OUTPUT_FILE = "build/test-insights/test-insights.json";
    private static final String SYSTEM_PROPERTY_FILE = "buildkite.test.insights.file";

    // Pattern to extract class name from JUnit 5 uniqueId: [class:fully.qualified.ClassName]
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\[class:([^\\]]+)\\]");
    // Pattern to extract method name from JUnit 5 uniqueId: [method:methodName]
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\[method:([^\\]]+)\\]");

    private final Set<TestResult> testResults = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<String, Instant> testStartTimes = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private Instant executionStartTime;
    private Path outputFile;

    @Override
    public void testPlanExecutionStarted(org.junit.platform.launcher.TestPlan testPlan) {
        executionStartTime = Instant.now();
        outputFile = determineOutputFile();

        try {
            Files.createDirectories(outputFile.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory for test insights", e);
        }
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (testIdentifier.isTest()) {
            testStartTimes.put(testIdentifier.getUniqueId(), Instant.now());
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
        if (!testIdentifier.isTest()) {
            return;
        }

        String uniqueId = testIdentifier.getUniqueId();
        Instant startTime = testStartTimes.remove(uniqueId);
        Instant endTime = Instant.now();

        Duration duration = calculateDuration(startTime, endTime);
        History history = new History(duration.startAtNanos, duration.endAtNanos, duration.durationSeconds);

        TestResult testResult = new TestResult(
                UUID.randomUUID().toString(),
                extractTestName(testIdentifier),
                extractScope(uniqueId),
                extractLocation(uniqueId),
                extractFileName(uniqueId),
                mapResultStatus(result.getStatus()),
                extractFailureReason(result),
                history);

        testResults.add(testResult);
    }

    @Override
    public void testPlanExecutionFinished(org.junit.platform.launcher.TestPlan testPlan) {
        writeResults();
    }

    private Path determineOutputFile() {
        String propertyValue = System.getProperty(SYSTEM_PROPERTY_FILE);
        if (propertyValue != null && !propertyValue.isEmpty()) {
            return Path.of(propertyValue);
        }
        return Path.of(DEFAULT_OUTPUT_FILE);
    }

    private String extractTestName(TestIdentifier testIdentifier) {
        String displayName = testIdentifier.getDisplayName();

        // Remove parameter info from parameterized tests (e.g., "[1]" from "testMethod[1]")
        int paramStart = displayName.indexOf('[');
        if (paramStart > 0 && displayName.endsWith("]")) {
            displayName = displayName.substring(0, paramStart);
        }

        // Remove JUnit 5 specific suffixes
        if (displayName.endsWith("()")) {
            displayName = displayName.substring(0, displayName.length() - 2);
        }

        return displayName.trim();
    }

    private String extractScope(String uniqueId) {
        Matcher classMatcher = CLASS_PATTERN.matcher(uniqueId);
        if (classMatcher.find()) {
            String className = classMatcher.group(1);
            int lastDot = className.lastIndexOf('.');
            return lastDot >= 0 ? className.substring(lastDot + 1) : className;
        }
        return "";
    }

    private String extractLocation(String uniqueId) {
        Matcher classMatcher = CLASS_PATTERN.matcher(uniqueId);
        if (classMatcher.find()) {
            String className = classMatcher.group(1);
            return "./" + className.replace('.', '/') + ".java";
        }
        return "";
    }

    private String extractFileName(String uniqueId) {
        String location = extractLocation(uniqueId);
        return location.isEmpty() ? location : "./" + location.substring(2);
    }

    private String mapResultStatus(TestExecutionResult.Status status) {
        return switch (status) {
            case SUCCESSFUL -> "passed";
            case FAILED -> "failed";
            case ABORTED -> "failed";
        };
    }

    private String extractFailureReason(TestExecutionResult result) {
        if (result.getStatus() == TestExecutionResult.Status.FAILED) {
            Optional<Throwable> throwableOpt = result.getThrowable();
            if (throwableOpt.isPresent()) {
                Throwable throwable = throwableOpt.get();
                String message = throwable.getMessage();
                if (message != null && !message.isEmpty()) {
                    return message;
                }
                return throwable.getClass().getSimpleName();
            }
        }
        return "";
    }

    private Duration calculateDuration(Instant startTime, Instant endTime) {
        Duration duration = new Duration();

        if (startTime != null && executionStartTime != null) {
            duration.startAtNanos = ChronoUnit.NANOS.between(executionStartTime, startTime) / 1_000_000_000.0;
        }

        if (endTime != null && executionStartTime != null) {
            duration.endAtNanos = ChronoUnit.NANOS.between(executionStartTime, endTime) / 1_000_000_000.0;
        }

        if (startTime != null && endTime != null) {
            duration.durationSeconds = ChronoUnit.NANOS.between(startTime, endTime) / 1_000_000_000.0;
        } else if (duration.startAtNanos > 0) {
            duration.durationSeconds = 0.0;
        }

        return duration;
    }

    private void writeResults() {
        List<TestResult> resultsToWrite = new ArrayList<>(testResults);

        try {
            objectMapper.writeValue(outputFile.toFile(), resultsToWrite);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write test insights JSON", e);
        }
    }

    private static class TestResult {
        private final String id;
        private final String name;
        private final String scope;
        private final String location;
        private final String fileName;
        private final String result;
        private final String failureReason;
        private final History history;

        public TestResult(
                String id,
                String name,
                String scope,
                String location,
                String fileName,
                String result,
                String failureReason,
                History history) {
            this.id = id;
            this.name = name;
            this.scope = scope;
            this.location = location;
            this.fileName = fileName;
            this.result = result;
            this.failureReason = failureReason;
            this.history = history;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getScope() { return scope; }
        public String getLocation() { return location; }
        public String getFileName() { return fileName; }
        public String getResult() { return result; }
        public String getFailureReason() { return failureReason; }
        public History getHistory() { return history; }
    }

    private static class History {
        private final double startAt;
        private final double endAt;
        private final double duration;

        public History(double startAt, double endAt, double duration) {
            this.startAt = startAt;
            this.endAt = endAt;
            this.duration = duration;
        }

        public double getStartAt() { return startAt; }
        public double getEndAt() { return endAt; }
        public double getDuration() { return duration; }
    }

    private static class Duration {
        double startAtNanos;
        double endAtNanos;
        double durationSeconds;
    }
}