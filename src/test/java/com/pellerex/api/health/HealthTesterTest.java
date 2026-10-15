package com.pellerex.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** The health test: what it tests, the one line it writes, and the result it keeps. */
class HealthTesterTest {

    private final Logger testerLogger = (Logger) LoggerFactory.getLogger(HealthTester.class);
    private final ListAppender<ILoggingEvent> written = new ListAppender<>();

    @BeforeEach
    void captureLines() {
        written.start();
        testerLogger.addAppender(written);
    }

    @AfterEach
    void stopCapturing() {
        testerLogger.detachAppender(written);
    }

    private static HealthCheck check(String name, ThrowingTest test) {
        return new HealthCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void test() throws Exception {
                test.run();
            }
        };
    }

    private interface ThrowingTest {
        void run() throws Exception;
    }

    private static HealthCheck passing(String name) {
        return check(name, () -> {});
    }

    private static HealthCheck failing(String name, String reason) {
        return check(name, () -> {
            throw new IllegalStateException(reason);
        });
    }

    /** The one line a single test wrote. */
    private ILoggingEvent onlyLine() {
        assertThat(written.list).as("one line per test").hasSize(1);
        return written.list.get(0);
    }

    /** Waits up to 5 seconds for the first test of an app that has just started. */
    private static void waitForTheFirstTest(HealthTester tester) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!tester.started() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void beforeTheFirstTestTheAppIsNeitherStartedNorHealthy() {
        HealthTester tester = new HealthTester(List.of());

        assertThat(tester.started()).isFalse();
        assertThat(tester.healthy()).isFalse();
        assertThat(written.list).isEmpty();
    }

    @Test
    void aNewAppHasNothingToTestAndIsHealthy() {
        HealthTester tester = new HealthTester(List.of());

        tester.test();

        assertThat(tester.started()).isTrue();
        assertThat(tester.healthy()).isTrue();
        assertThat(onlyLine().getLevel()).isEqualTo(Level.INFO);
        assertThat(onlyLine().getFormattedMessage()).isEqualTo("Health test passed: nothing to test");
    }

    @Test
    void passingChecksAreWrittenWithHowLongEachTook() {
        HealthTester tester = new HealthTester(List.of(passing("database"), passing("cache")));

        tester.test();

        assertThat(tester.healthy()).isTrue();
        assertThat(onlyLine().getLevel()).isEqualTo(Level.INFO);
        assertThat(onlyLine().getFormattedMessage())
                .matches("Health test passed: database \\d+ ms, cache \\d+ ms");
    }

    @Test
    void oneFailedCheckMakesTheAppUnhealthyAndSaysWhy() {
        HealthTester tester =
                new HealthTester(List.of(failing("database", "connection refused"), passing("cache")));

        tester.test();

        assertThat(tester.started()).as("started once the first test has finished").isTrue();
        assertThat(tester.healthy()).isFalse();
        assertThat(onlyLine().getLevel()).isEqualTo(Level.WARN);
        assertThat(onlyLine().getFormattedMessage())
                .matches(
                        "Health test failed: database failed after \\d+ ms \\(connection refused\\),"
                                + " cache \\d+ ms");
    }

    @Test
    void aFailureWithNoMessageIsNamedByItsKind() {
        HealthTester tester =
                new HealthTester(List.of(check("database", () -> {
                    throw new IOException();
                })));

        tester.test();

        assertThat(onlyLine().getFormattedMessage())
                .matches("Health test failed: database failed after \\d+ ms \\(IOException\\)");
    }

    @Test
    void aCheckThatThrowsAnErrorHasFailed() {
        HealthTester tester =
                new HealthTester(List.of(check("database", () -> {
                    throw new OutOfMemoryError("Java heap space");
                })));

        tester.test();

        assertThat(tester.healthy()).isFalse();
        assertThat(onlyLine().getFormattedMessage())
                .matches(
                        "Health test failed: database failed after \\d+ ms"
                                + " \\(java.lang.OutOfMemoryError: Java heap space\\)");
    }

    @Test
    void aCheckThatDoesNotAnswerHasFailedAndIsInterrupted() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        HealthCheck hanging = check("database", () -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException stopped) {
                interrupted.countDown();
            }
        });
        HealthTester tester = new HealthTester(List.of(hanging), Duration.ofMillis(50));

        tester.test();

        assertThat(tester.healthy()).isFalse();
        assertThat(onlyLine().getFormattedMessage())
                .isEqualTo("Health test failed: database failed after 50 ms (did not answer within 50 ms)");
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the hung check was interrupted").isTrue();
    }

    @Test
    void theAppIsHealthyAgainOnceItsChecksPass() {
        AtomicBoolean databaseUp = new AtomicBoolean(false);
        HealthTester tester = new HealthTester(List.of(check("database", () -> {
            if (!databaseUp.get()) {
                throw new IllegalStateException("connection refused");
            }
        })));

        tester.test();
        assertThat(tester.healthy()).isFalse();

        databaseUp.set(true);
        tester.test();
        assertThat(tester.healthy()).isTrue();
    }

    /** The chart's Kubernetes checks count failed tests by these, so a change must fail a test. */
    @Test
    void theTestRunsOnceAMinuteAndACheckHasTenSeconds() {
        assertThat(HealthTester.TEST_INTERVAL).isEqualTo(Duration.ofMinutes(1));
        assertThat(HealthTester.CHECK_TIME_LIMIT).isEqualTo(Duration.ofSeconds(10));
    }

    /** A new app has no HealthCheck bean: Spring still builds the tester, which has nothing to test. */
    @Test
    void springBuildsTheTesterOfANewAppWithNothingToTest() {
        new ApplicationContextRunner()
                .withUserConfiguration(HealthTester.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HealthTester tester = context.getBean(HealthTester.class);
                    waitForTheFirstTest(tester);

                    assertThat(tester.healthy()).isTrue();
                    assertThat(written.list)
                            .extracting(ILoggingEvent::getFormattedMessage)
                            .containsExactly("Health test passed: nothing to test");
                });
    }

    @Configuration
    static class WithADatabaseCheck {
        @Bean
        HealthCheck databaseCheck() {
            return passing("database");
        }
    }

    /** Spring hands the tester every HealthCheck bean and runs the first test as the app starts. */
    @Test
    void springTestsEveryHealthCheckBeanAsTheAppStarts() {
        new ApplicationContextRunner()
                .withUserConfiguration(WithADatabaseCheck.class, HealthTester.class)
                .run(context -> {
                    HealthTester tester = context.getBean(HealthTester.class);
                    waitForTheFirstTest(tester);

                    assertThat(tester.healthy()).isTrue();
                    assertThat(written.list)
                            .extracting(ILoggingEvent::getFormattedMessage)
                            .singleElement(STRING)
                            .matches("Health test passed: database \\d+ ms");
                });
    }

    @Configuration
    @EnableScheduling
    static class WithAStuckScheduledJob {
        /** A job of the app that never finishes, so it holds Spring's one scheduler thread. */
        @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
        void importEverything() {
            try {
                Thread.sleep(Duration.ofHours(1));
            } catch (InterruptedException stopped) {
                // The app stops: the job ends without an error, so the test's output shows none
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The test runs on a thread of its own, so a stuck scheduled job of the app cannot hold it up. */
    @Test
    void aStuckScheduledJobOfTheAppDoesNotHoldTheTestUp() {
        new ApplicationContextRunner()
                .withUserConfiguration(WithAStuckScheduledJob.class, HealthTester.class)
                .run(context -> {
                    HealthTester tester = context.getBean(HealthTester.class);
                    waitForTheFirstTest(tester);

                    assertThat(tester.healthy()).isTrue();
                    assertThat(onlyLine().getThreadName()).startsWith(HealthTester.TEST_THREAD_NAME_PREFIX);
                });
    }

    /** A test still running as the app shuts down stops: it writes no line and keeps no result. */
    @Test
    void aTestStillRunningAsTheAppShutsDownWritesNoLine() throws InterruptedException {
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        HealthCheck hanging = check("database", () -> {
            checking.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException stopped) {
                interrupted.countDown();
            }
        });
        AtomicReference<HealthTester> tester = new AtomicReference<>();

        new ApplicationContextRunner()
                .withBean(HealthCheck.class, () -> hanging)
                .withUserConfiguration(HealthTester.class)
                .run(context -> {
                    assertThat(checking.await(5, TimeUnit.SECONDS)).as("the check is running").isTrue();
                    tester.set(context.getBean(HealthTester.class));
                });

        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the running check was interrupted").isTrue();
        assertThat(tester.get().isRunning()).isFalse();
        assertThat(tester.get().started()).as("the stopped test kept no result").isFalse();
        assertThat(written.list).as("the stopped test wrote no line").isEmpty();
    }
}
