package co.rsk.logger;

import java.util.HashMap;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;


public final class LoggingFilter extends Filter<ILoggingEvent> {

    private final Map<Integer, LogEvent> events;
    private double rate;

    public LoggingFilter() {
        this.events = new HashMap<>();
        this.rate = 0;
    }

    public double getRate() {
        return rate;
    }

    public void setRate(double rate) {
        this.rate = rate;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!isStarted())
            return FilterReply.NEUTRAL;
        if (filter(event))
            return FilterReply.NEUTRAL;
        else
            return FilterReply.DENY;
    }

    private boolean filter(ILoggingEvent event) {
        Marker marker = event.getMarker();
        if (!(marker instanceof LoggingMarker))
            return true;
        int eventHash = getEventHash(event);
        LogEvent logEvent = events.get(eventHash);
        if (logEvent == null) {
            events.put(eventHash, new LogEvent(event.getTimeStamp(), 1));
            return true;
        }
        if (logEvent.count / ((event.getTimeStamp() - logEvent.first) / 1000.0) <= rate) {
            ++logEvent.count;
            return true;
        } else {
            return false;
        }
    }

    private static int getEventHash(ILoggingEvent event) {
        return (31 * event.getLoggerName().hashCode() + event.getMessage().hashCode());
    }

    static class LogEvent {

        final long first;
        long count;

        LogEvent(long first, long count) {
            this.first = first;
            this.count = count;
        }
    }
}
