package com.pellerex.api.health;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Runs the app's health test and keeps its last result. Straight away and then once a minute it
 * tests every {@link HealthCheck} bean at the same time, writes one log line saying what was tested
 * and how long each part took, and keeps whether all of them passed. The health addresses answer
 * from that result ({@code HealthController}), so no number of calls to them costs the checked
 * dependencies anything.
 *
 * <p>Tenant apps test once a minute, not every 30 seconds as the platform services do: the
 * databases Pellerex creates for them share one pool, so every test of every app adds to it. The
 * chart's Kubernetes checks call once a minute too (infrastructure/Helm/templates/deployment.yaml).
 *
 * <p>The test runs on a thread of its own, started and stopped with the app. Spring's scheduler
 * runs every scheduled method of an app on one shared thread, so a long or stuck job there would
 * hold the test up while the health addresses kept answering from an old result.
 */
@Component
public class HealthTester implements SmartLifecycle {

    /** How often the test runs. */
    static final Duration TEST_INTERVAL = Duration.ofMinutes(1);

    /** How long one check may take. A check that has not answered by then has failed. */
    static final Duration CHECK_TIME_LIMIT = Duration.ofSeconds(10);

    /** The start of the name of the thread the test runs on, as a thread dump shows it. */
    static final String TEST_THREAD_NAME_PREFIX = "health-test-";

    private static final Logger log = LoggerFactory.getLogger(HealthTester.class);

    /** What one check of one test found: how long it took and, when it failed, why. */
    private record CheckResult(String name, long milliseconds, String failure) {}

    private final List<HealthCheck> checks;
    private final Duration timeLimit;
    // A hung check holds only a virtual thread, and is interrupted when its time is up.
    private final ExecutorService checkRunner = Executors.newVirtualThreadPerTaskExecutor();

    // The test's own thread: made when the app starts, ended when it shuts down.
    private ThreadPoolTaskScheduler testRunner;

    private volatile boolean finished;
    private volatile boolean passed;

    @Autowired
    public HealthTester(List<HealthCheck> checks) {
        this(checks, CHECK_TIME_LIMIT);
    }

    HealthTester(List<HealthCheck> checks, Duration timeLimit) {
        this.checks = List.copyOf(checks);
        this.timeLimit = timeLimit;
    }

    /** Starts the test as the app starts: straight away and then once a minute. */
    @Override
    public synchronized void start() {
        this.testRunner = new ThreadPoolTaskScheduler();
        this.testRunner.setThreadNamePrefix(TEST_THREAD_NAME_PREFIX);
        this.testRunner.setDaemon(true);
        this.testRunner.initialize();
        this.testRunner.scheduleAtFixedRate(this::test, TEST_INTERVAL);
    }

    /** Stops the test as the app shuts down. A test still running stops and writes no line. */
    @Override
    public synchronized void stop() {
        this.testRunner.shutdown();
        this.testRunner = null;
    }

    /** Whether the test runs: from the app's start until it shuts down. */
    @Override
    public synchronized boolean isRunning() {
        return this.testRunner != null;
    }

    /** One health test. While the app runs, the test's own thread runs it once a minute. */
    public void test() {
        long testStart = System.nanoTime();
        List<Callable<CheckResult>> runs = new ArrayList<>();
        for (HealthCheck check : this.checks) {
            runs.add(() -> testCheck(check));
        }

        List<Future<CheckResult>> answers;
        try {
            // Waits until every check has answered or the time limit is up, and cancels the rest
            answers = this.checkRunner.invokeAll(runs, this.timeLimit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException stopped) {
            // The app is shutting down. A test stopped part way has found nothing, so it writes no
            // line, no check is said to have failed, and the last result stands
            Thread.currentThread().interrupt();
            return;
        }

        finish(answers, testStart);
    }

    /** Whether the first test has finished, whatever it found. */
    public boolean started() {
        return this.finished;
    }

    /** Whether the last test passed. Before the first test has finished it is false. */
    public boolean healthy() {
        return this.finished && this.passed;
    }

    /** Finishes a test: writes its one line and keeps whether every check passed. */
    private void finish(List<Future<CheckResult>> answers, long testStart) {
        boolean allPassed = true;
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < this.checks.size(); index++) {
            CheckResult result = resultOf(this.checks.get(index), answers.get(index), testStart);
            allPassed &= result.failure() == null;
            parts.add(describe(result));
        }

        String summary = parts.isEmpty() ? "nothing to test" : String.join(", ", parts);
        if (allPassed) {
            log.info("Health test passed: {}", summary);
        } else {
            log.warn("Health test failed: {}", summary);
        }

        this.passed = allPassed;
        this.finished = true;
    }

    /**
     * Runs one check. The check reaches something outside the app, such as its database, so what
     * it throws is its answer, not an error of the app.
     */
    private static CheckResult testCheck(HealthCheck check) {
        long start = System.nanoTime();
        try {
            check.test();
            return new CheckResult(check.name(), millisecondsSince(start), null);
        } catch (Exception failure) {
            String why = Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
            return new CheckResult(check.name(), millisecondsSince(start), why);
        }
    }

    /** What a check found, once every check has answered or the time limit is up. */
    private CheckResult resultOf(HealthCheck check, Future<CheckResult> answer, long testStart) {
        return switch (answer.state()) {
            case SUCCESS -> answer.resultNow();
            // Only an Error gets past testCheck, such as running out of memory
            case FAILED -> new CheckResult(
                    check.name(), millisecondsSince(testStart), answer.exceptionNow().toString());
            default -> new CheckResult(
                    check.name(),
                    this.timeLimit.toMillis(),
                    "did not answer within " + this.timeLimit.toMillis() + " ms");
        };
    }

    /** "database 12 ms", or "database failed after 12 ms (why)". */
    private static String describe(CheckResult result) {
        return result.failure() == null
                ? result.name() + " " + result.milliseconds() + " ms"
                : result.name() + " failed after " + result.milliseconds() + " ms ("
                        + result.failure() + ")";
    }

    private static long millisecondsSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
