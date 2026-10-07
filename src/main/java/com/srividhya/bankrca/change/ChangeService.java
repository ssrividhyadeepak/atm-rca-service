package com.srividhya.bankrca.change;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.srividhya.bankrca.security.PiiMasker;

/**
 * "What changed?": the change requests for a set of components in a window and, when the
 * time the failures started is known, how each change sits against it. The timing is
 * arithmetic, done here; whether a change is the cause is for the reader to argue from it.
 */
@Service
public class ChangeService {

    /**
     * @param timing BEFORE_FAILURE (finished before the failures started), DURING_FAILURE_START
     *        (in progress when they started), AFTER_FAILURE (started later, so not the cause);
     *        null when no failure time was given
     * @param minutesBeforeFailure from the end of the change to the first failure; 0 when in progress; null otherwise
     * @param timingNote the same in words
     */
    public record TimedChange(ChangeRequest change, String timing, Long minutesBeforeFailure, String timingNote) {
    }

    /** @param changes with a failure time: the closest before the failure first; without: newest first */
    public record ChangeSearch(String from, String to, List<String> components, String failureTime, String source,
            List<TimedChange> changes) {
    }

    public static final int MAX_CHANGES = 50;
    private static final int MAX_DESCRIPTION = 600;
    // What may go into the query sent to the change system
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final ChangeClient client;
    private final PiiMasker masker;

    public ChangeService(ChangeClient client, PiiMasker masker) {
        this.client = client;
        this.masker = masker;
    }

    public String description() {
        return client.description();
    }

    /**
     * @param failureTime when the failures started, or null
     * @throws IllegalArgumentException for a component name that is not a plain name
     */
    public ChangeSearch search(List<String> components, Instant from, Instant to, Instant failureTime) {
        for (String c : components) {
            if (c == null || !COMPONENT.matcher(c).matches()) {
                throw new IllegalArgumentException("'component' must be a component name such as withdrawal-p1");
            }
        }
        List<TimedChange> timed = new ArrayList<>();
        for (ChangeRequest c : client.changes(components, from, to, MAX_CHANGES)) {
            timed.add(timed(masked(c), failureTime));
        }
        if (failureTime != null) {
            // Finished shortly before the failure first; then in-flight; started-after last
            timed.sort(Comparator.comparingInt((TimedChange t) -> "AFTER_FAILURE".equals(t.timing()) ? 1 : 0)
                    .thenComparingLong(t -> t.minutesBeforeFailure() == null ? Long.MAX_VALUE : t.minutesBeforeFailure()));
        }
        return new ChangeSearch(from.toString(), to.toString(), List.copyOf(components),
                failureTime == null ? null : failureTime.toString(), client.description(), timed);
    }

    private static TimedChange timed(ChangeRequest c, Instant failure) {
        if (failure == null || c.startedAt() == null) {
            return new TimedChange(c, null, null, null);
        }
        Instant start = Instant.parse(c.startedAt());
        Instant end = c.endedAt() == null ? start : Instant.parse(c.endedAt());
        if (start.isAfter(failure)) {
            return new TimedChange(c, "AFTER_FAILURE", null, "started " + span(Duration.between(failure, start))
                    + " after the failures began, so it did not cause them");
        }
        if (!end.isBefore(failure)) {
            return new TimedChange(c, "DURING_FAILURE_START", 0L, "was in progress when the failures began");
        }
        Duration gap = Duration.between(end, failure);
        return new TimedChange(c, "BEFORE_FAILURE", gap.toMinutes(), "finished " + span(gap) + " before the failures began");
    }

    private static String span(Duration d) {
        long minutes = d.toMinutes();
        return minutes < 120 ? minutes + " min" : minutes < 2880 ? (minutes / 60) + "h " + (minutes % 60) + "min"
                : (minutes / 1440) + " days";
    }

    /** Free text written by people: masked, and cut to a readable length. */
    private ChangeRequest masked(ChangeRequest c) {
        String description = masker.mask(c.description());
        if (description != null && description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION) + "...";
        }
        return new ChangeRequest(c.number(), c.component(), c.service(), c.type(), c.state(), c.risk(),
                masker.mask(c.shortDescription()), description, c.assignmentGroup(), c.startedAt(), c.endedAt(),
                c.closeCode());
    }
}
