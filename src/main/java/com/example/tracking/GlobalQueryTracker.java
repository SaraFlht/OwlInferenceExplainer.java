package com.example.tracking;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalQueryTracker {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalQueryTracker.class);

    private static final Set<String> globalProcessedQueries = ConcurrentHashMap.newKeySet();
    private static final Set<String> globalTripleKeys = ConcurrentHashMap.newKeySet();

    public static boolean addQuery(String normalizedQuery) {
        boolean isNew = globalProcessedQueries.add(normalizedQuery);
        if (!isNew) {
            LOGGER.debug("Duplicate query detected and skipped: {}",
                    normalizedQuery.length() > 100 ?
                            normalizedQuery.substring(0, 100) + "..." : normalizedQuery);
        }
        return isNew;
    }

    public static boolean addTriple(String normalizedTriple) {
        boolean isNew = globalTripleKeys.add(normalizedTriple);
        if (!isNew) {
            LOGGER.debug("Duplicate triple detected and skipped: {}", normalizedTriple);
        }
        return isNew;
    }

    public static void reset() {
        int queryCount = globalProcessedQueries.size();
        int tripleCount = globalTripleKeys.size();

        globalProcessedQueries.clear();
        globalTripleKeys.clear();

        LOGGER.info("Global query tracker reset. Previous session stats - Queries: {}, Triples: {}",
                queryCount, tripleCount);
    }

    public static int getQueryCount() {
        return globalProcessedQueries.size();
    }

    public static int getTripleCount() {
        return globalTripleKeys.size();
    }

    public static void logStats() {
        LOGGER.info("Global Query Tracker Stats - Unique Queries: {}, Unique Triples: {}",
                getQueryCount(), getTripleCount());
    }

    public static void logMemoryUsage() {
        // Rough estimate: each string averages ~100 characters, 2 bytes per char in Java
        long estimatedMemoryMB = (globalProcessedQueries.size() + globalTripleKeys.size()) * 100 * 2 / (1024 * 1024);
        LOGGER.info("Estimated tracker memory usage: ~{} MB", estimatedMemoryMB);
    }
}