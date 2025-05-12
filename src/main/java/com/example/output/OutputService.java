package com.example.output;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Interface for services that output query data.
 */
public interface OutputService extends AutoCloseable {
    /**
     * Initialize the output service.
     */
    void initialize() throws IOException;

    /**
     * Write a binary (yes/no) query to the output.
     */
    void writeBinaryQuery(String taskType,
                          String sparql,
                          String predicate,
                          String answer,
                          String explanation,
                          int size) throws IOException;

    /**
     * Write a multiple choice query to the output.
     */
    void writeMultiChoiceQuery(String taskType,
                               String sparql,
                               String predicate,
                               String answer,
                               String explanation,
                               int size) throws IOException;

    /**
     * Write a multiple choice query with grouped answers to the output.
     * Default implementation falls back to writing individual answers.
     */
    default void writeGroupedMultiChoiceQuery(String taskType,
                                              String sparql,
                                              String predicate,
                                              List<String> answers,
                                              Map<String, String> explanationMap,
                                              Map<String, Integer> sizeMap) throws IOException {
        // Default implementation falls back to writing multiple individual entries
        for (String answer : answers) {
            writeMultiChoiceQuery(
                    taskType,
                    sparql,
                    predicate,
                    answer,
                    explanationMap.get(answer),
                    sizeMap.getOrDefault(answer, 1)
            );
        }
    }
}