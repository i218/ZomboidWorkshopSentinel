package io.workshopsentinel;

import java.util.Set;

/** Called exclusively on the single I/O worker. Errors must throw, never mean 'no updates'. */
@FunctionalInterface
public interface WorkshopUpdateProvider {
    Set<String> check(Set<String> configuredIds) throws Exception;
}
