package com.pellerex.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pellerex.api.web.CorrelationIdFilter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A request's correlation id is the caller's when that is safe to log and a new one otherwise; it
 *  goes back on the reply and onto every log record written while the request is served. */
class CorrelationIdFilterTest {

    // Written out on purpose: the platform's proxy sends the header and the portal's Logs tab reads
    // the log field by these exact names, so a rename must fail a test.
    private static final String HEADER = "X-Correlation-Id";
    private static final String LOG_FIELD = "CorrelationId";

    private static final String NEW_ID = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    private final CorrelationIdFilter filter = new CorrelationIdFilter("development", "8080");
    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
    private final ListAppender<ILoggingEvent> requestLines = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            // The filter empties the MDC when the request ends, so its entries are read now
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    /** What one call through the filter showed: the id on the reply, the id a log record written
     *  while the request was served carries, and the id on the request line. */
    private record Served(String onReply, String whileServed, String onRequestLine) {}

    @BeforeEach
    void captureRequestLines() {
        requestLines.start();
        filterLogger.addAppender(requestLines);
    }

    @AfterEach
    void stopCapturing() {
        filterLogger.detachAppender(requestLines);
    }

    @Test
    void theCallersIdIsReturnedAndCarriedByEveryLogRecordOfTheRequest() throws Exception {
        // One of every kind of character an id may hold, with both ends of each range
        String sent = "azAZ09-_.Order-42";

        Served served = serve(sent);

        assertThat(served.onReply()).isEqualTo(sent);
        assertThat(served.whileServed()).isEqualTo(sent);
        assertThat(served.onRequestLine()).isEqualTo(sent);
    }

    @Test
    void aCallWithoutAnIdIsGivenANewOne() throws Exception {
        Served served = serve(null);

        assertThat(served.onReply()).matches(NEW_ID);
        assertThat(served.whileServed()).isEqualTo(served.onReply());
        assertThat(served.onRequestLine()).isEqualTo(served.onReply());
    }

    @Test
    void theLongestIdTakenAsSentHas128Characters() throws Exception {
        String sent = "a".repeat(128);

        assertThat(serve(sent).onReply()).isEqualTo(sent);
    }

    @ParameterizedTest
    @MethodSource("idsNotSafeToLog")
    void anIdThatIsNotSafeToLogIsReplaced(String sent) throws Exception {
        Served served = serve(sent);

        assertThat(served.onReply()).matches(NEW_ID);
        assertThat(served.whileServed()).isEqualTo(served.onReply());
        assertThat(served.onRequestLine()).isEqualTo(served.onReply());
    }

    private static Stream<String> idsNotSafeToLog() {
        return Stream.of(
                "",
                " ",
                "a".repeat(129),
                "abc def",
                "abc,abc",
                "abc\ndef",
                "abc\n",
                "abcïdef",
                "<script>",
                // The characters just outside each range an id may hold
                "abc/def",
                "abc:def",
                "abc@def",
                "abc[def",
                "abc`def",
                "abc{def");
    }

    /** Kubernetes calls the health addresses once a minute and the health test writes its own
     *  line, so a call to them writes no request line. */
    @ParameterizedTest
    @ValueSource(strings = {"/health/startup", "/health/live", "/health/ready"})
    void aCallToAHealthAddressWritesNoRequestLine(String path) throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", path),
                new MockHttpServletResponse(),
                (servedRequest, servedResponse) -> {});

        assertThat(requestLines.list).isEmpty();
        assertThat(MDC.getCopyOfContextMap()).as("the request's entries are cleared").isNullOrEmpty();
    }

    /** Only the three health addresses are left out: one that merely starts like them is not. */
    @Test
    void anAddressNextToAHealthAddressKeepsItsRequestLine() throws Exception {
        filter.doFilter(
                new MockHttpServletRequest("GET", "/health/readyz"),
                new MockHttpServletResponse(),
                (servedRequest, servedResponse) -> {});

        assertThat(requestLines.list).hasSize(1);
        assertThat(requestLines.list.get(0).getMDCPropertyMap()).containsEntry("RequestPath", "/health/readyz");
    }

    private Served serve(String sent) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/hello");
        if (sent != null) {
            request.addHeader(HEADER, sent);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> whileServed = new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (servedRequest, servedResponse) -> whileServed.set(MDC.get(LOG_FIELD)));

        assertThat(requestLines.list).hasSize(1);
        return new Served(
                response.getHeader(HEADER),
                whileServed.get(),
                requestLines.list.get(0).getMDCPropertyMap().get(LOG_FIELD));
    }
}
