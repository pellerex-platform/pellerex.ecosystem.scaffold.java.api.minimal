package com.pellerex.api.health;

/**
 * One thing the app cannot work without, such as its database. A new app has none. Add one as a
 * Spring bean (a {@code @Component} implementing this interface) and {@link HealthTester} tests it
 * once a minute with the others.
 */
public interface HealthCheck {

    /** The name the health test's log line gives this check, such as {@code database}. */
    String name();

    /**
     * Returns when the thing works and throws, saying why, when it does not. It is given 10
     * seconds, then it has failed and its thread is interrupted.
     */
    void test() throws Exception;
}
