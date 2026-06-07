package org.jenkinsci.gradle.plugins.jpi;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public class IntegrationTestHelper {

    private static final String TASK_TRACING_INIT_SCRIPT = "org/jenkinsci/gradle/plugins/jpi2/task-tracing.init.gradle";
    private static final String TASK_TRACING_OUTPUT_PROPERTY = "buildkite.task.tracing.output";
    private static final String TASK_SPANS_FILE_PROPERTY = "buildkite.task.spans.file";

    private final File projectDir;

    private final String minimumGradleVersion;

    public IntegrationTestHelper(File projectDir, String minimumGradleVersion) {
        this.projectDir = projectDir;
        this.minimumGradleVersion = minimumGradleVersion;
    }

    public IntegrationTestHelper(File projectDir) {
        this(projectDir, null);
    }

    public GradleRunner gradleRunner() throws IOException {
        return gradleRunner(WarningMode.ALL);
    }

    public GradleRunner gradleRunner(WarningMode warningMode) throws IOException {
        var gradleProperties = inProjectDir("gradle.properties");
        if (!existsRelativeToProjectDir("gradle.properties")) {
            var props = new Properties();
            props.setProperty("org.gradle.warning.mode", warningMode.name().toLowerCase(Locale.US));
            try (var outputStream = new FileOutputStream(gradleProperties)) {
                props.store(outputStream, "IntegrationSpec default generated values");
            }
        }
        var runner = GradleRunner.create()
                .withPluginClasspath()
                .withProjectDir(projectDir);
        var gradleVersion = getGradleVersionForTest(minimumGradleVersion);
        if (gradleVersion != GradleVersion.current()) {
            runner = runner.withGradleVersion(gradleVersion.getVersion());
        }

        // Configure task tracing for Buildkite Test Insights
        File buildDir = inProjectDir("build");
        Files.createDirectories(buildDir.toPath());
        File taskSpansFile = new File(buildDir, "test-insights/task-spans.json");

        // Add task tracing init script for Buildkite Test Insights via arguments
        File initScriptFile = createTaskTracingInitScript();
        List<String> args = new ArrayList<>();
        args.add("-Dorg.gradle.deprecation.trace=true");
        args.add("-D" + TASK_TRACING_OUTPUT_PROPERTY + "=" + taskSpansFile.getAbsolutePath());
        // Also set for the listener to read task spans
        args.add("-D" + TASK_SPANS_FILE_PROPERTY + "=" + taskSpansFile.getAbsolutePath());
        args.add("--init-script");
        args.add(initScriptFile.getAbsolutePath());
        runner = runner.withArguments(args);

        return runner;
    }

    private File createTaskTracingInitScript() throws IOException {
        // Create init script in project's build directory to avoid polluting source tree
        File buildDir = inProjectDir("build");
        Files.createDirectories(buildDir.toPath());
        File initScript = new File(buildDir, "task-tracing.init.gradle");

        // Only copy if not already present or if resource has changed
        var resourceStream = getClass().getClassLoader().getResourceAsStream(TASK_TRACING_INIT_SCRIPT);
        if (resourceStream == null) {
            throw new IOException("Task tracing init script not found on classpath: " + TASK_TRACING_INIT_SCRIPT);
        }
        try (resourceStream) {
            Files.copy(resourceStream, initScript.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return initScript;
    }


    public static GradleVersion getGradleVersionForTest(String minimumGradleVersion) {
        String gradleUnderTest = System.getProperty("gradle.under.test");
        var targetVersion = gradleUnderTest == null ? GradleVersion.current() : GradleVersion.version(gradleUnderTest);
        if (minimumGradleVersion == null) {
            return targetVersion;
        }
        var minimumVersion = GradleVersion.version(minimumGradleVersion);
        return targetVersion.compareTo(minimumVersion) < 0 ? minimumVersion : targetVersion;
    }

    public static boolean isBeforeJavaConventionDeprecation() {
        return getGradleVersionForTest(null).compareTo(GradleVersion.version("8.2")) < 0;
    }

    public static boolean isAfterJavaConventionDeprecation() {
        return !isBeforeJavaConventionDeprecation();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    public boolean existsRelativeToProjectDir(String path) {
        return inProjectDir(path).exists();
    }

    public File inProjectDir(String path) {
        return new File(projectDir, path);
    }

    public File mkDirInProjectDir(String path) throws IOException {
        return Files.createDirectories(projectDir.toPath().resolve(path)).toFile();
    }

    public File touchInProjectDir(String path) throws IOException {
        return Files.createFile(projectDir.toPath().resolve(path)).toFile();
    }

}
