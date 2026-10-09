package io.workshopsentinel;

import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.logging.*;

/** Bounded, per-operation diagnostics; never changes permissions or hides a failed operation. */
public final class FailureReporter {
    private static final long REMINDER_MILLIS = 600000;
    private final Logger log;
    private final LongSupplier clock;
    private final Map<String, Failure> failures = new HashMap<>();
    private static final class Failure {
        String signature; long reported; int suppressed;
        Failure(String signature, long reported) { this.signature = signature; this.reported = reported; }
    }
    public FailureReporter(Logger log) { this(log, () -> System.nanoTime() / 1000000); }
    FailureReporter(Logger log, LongSupplier clock) { this.log = log; this.clock = clock; }
    public synchronized void failed(String operation, String nextStep, Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String signature = cause.getClass().getName() + ":" + cause.getMessage();
        long now = clock.getAsLong();
        Failure old = failures.get(operation);
        if (old != null && old.signature.equals(signature) && now - old.reported < REMINDER_MILLIS) {
            old.suppressed++; return;
        }
        String detail;
        if (cause instanceof AccessDeniedException || cause instanceof SecurityException)
            detail = "Access denied; check the service account's permissions, read-only attributes and file locks";
        else if (cause instanceof NoSuchFileException) detail = "File missing; check the configured path";
        else if (cause instanceof FileAlreadyExistsException) detail = "Path occupied; check whether a directory or file is expected";
        else if (cause instanceof FileSystemException) detail = "File operation failed; check path type, locks and disk availability";
        else detail = cause.getClass().getSimpleName();
        String message = operation + " failed: " + detail + "; " + cause.getMessage() + "; " + nextStep;
        if (old != null && old.suppressed > 0) message += "; repeated failures suppressed=" + old.suppressed;
        failures.put(operation, new Failure(signature, now));
        if (cause instanceof FileSystemException || cause instanceof SecurityException)
            log.warning(message);
        else log.log(Level.WARNING, message, error);
    }
    public synchronized void recovered(String operation) {
        Failure previous = failures.remove(operation);
        if (previous != null) log.info(operation + " recovered; repeated failures suppressed=" + previous.suppressed);
    }
}
