package com.example.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A hybrid implementation of OutputService that writes query metadata to CSV
 * and explanations to JSON in the specified format.
 */
@Component
public class HybridOutputService implements OutputService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HybridOutputService.class);

    private CSVPrinter csvPrinter;
    private final ObjectMapper jsonMapper;

    @Value("${output.csv.path:SPARQL_questions.csv}")
    private String csvFilePath;

    @Value("${output.json.path:explanations.json}")
    private String jsonFilePath;

    private final Map<String, ObjectNode> explanationsMap;

    // Triple-based mapping to group identical explanations
    private final Map<String, Set<String>> tripleToSparqlQueries;
    private final Map<String, ObjectNode> tripleExplanationsMap;

    private boolean initialized = false;
    private boolean closed = false;

    // Use ConcurrentHashMap for thread safety
    private final Set<String> processedQueries = ConcurrentHashMap.newKeySet();

    // Pattern to extract subject-predicate-object from explanations
    private static final Pattern TRIPLE_PATTERN = Pattern.compile("([\\w\\s]+)\\s+(\\w+)\\s+([\\w\\s]+)");

    public HybridOutputService() {
        this.jsonMapper = new ObjectMapper();
        this.explanationsMap = new HashMap<>();
        this.tripleToSparqlQueries = new HashMap<>();
        this.tripleExplanationsMap = new HashMap<>();
        LOGGER.info("Hybrid output service created");
    }

    @Override
    public synchronized void initialize() throws IOException {
        if (initialized || closed) {
            return;
        }

        LOGGER.info("Initializing Hybrid output service with CSV: {}, JSON: {}", csvFilePath, jsonFilePath);

        try {
            // Create parent directories if they don't exist
            Path csvPath = Path.of(csvFilePath);
            Path jsonPath = Path.of(jsonFilePath);

            Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
            Files.createDirectories(jsonPath.getParent() != null ? jsonPath.getParent() : Path.of("."));

            // Initialize CSV printer
            BufferedWriter csvWriter = Files.newBufferedWriter(
                    csvPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            this.csvPrinter = new CSVPrinter(csvWriter, CSVFormat.DEFAULT
                    .withHeader("Task Type", "Answer Type", "SPARQL Query", "Predicate", "Answer")
                    .withQuoteMode(QuoteMode.ALL)
                    .withEscape('\\')
                    .withRecordSeparator("\n"));

            initialized = true;
            LOGGER.info("Hybrid output service initialized");
        } catch (IOException e) {
            LOGGER.error("Failed to initialize output service", e);
            throw e;
        }
    }

    @Override
    public synchronized void writeBinaryQuery(String taskType,
                                              String sparql,
                                              String predicate,
                                              String answer,
                                              String explanation,
                                              int size) throws IOException {
        if (!initialized || closed) {
            initialize();
        }

        // Skip if we've already processed this SPARQL query
        if (processedQueries.contains(sparql)) {
            return;
        }

        try {
            // Write basic info to CSV
            csvPrinter.printRecord(taskType, "Binary", sparql, predicate, answer);
            csvPrinter.flush();

            // Mark as processed
            processedQueries.add(sparql);

            // Extract triple components based on task type
            String subject = "";
            String object = "";

            if (taskType.equals("Subsumption")) {
                if (sparql.contains("subClassOf")) {
                    String[] parts = sparql.split("subClassOf");
                    if (parts.length >= 2) {
                        subject = extractEntity(parts[0]);
                        object = extractEntity(parts[1]);
                    }
                }
            } else if (taskType.equals("Property Assertion")) {
                if (sparql.contains("{") && sparql.contains("}")) {
                    String[] parts = sparql.split("\\{|\\}")[1].split("\\s+");
                    if (parts.length >= 4) {
                        subject = cleanEntity(parts[1]);
                        object = cleanEntity(parts[3]);
                    }
                }
            } else if (taskType.equals("Membership")) {
                if (sparql.contains("{") && sparql.contains("}")) {
                    String[] parts = sparql.split("\\{|\\}")[1].split("\\s+");
                    if (parts.length >= 4 && parts[2].contains("type")) {
                        subject = cleanEntity(parts[1]);
                        object = cleanEntity(parts[3]);
                    }
                }
            }

            // Create a unique triple key for grouping
            String tripleKey = subject + "|" + predicate + "|" + object;

            // Add this query to the set of queries for this triple
            tripleToSparqlQueries.computeIfAbsent(tripleKey, k -> new HashSet<>()).add(sparql);

            // Process and store explanation for JSON
            processExplanation(sparql, tripleKey, taskType, predicate, subject, object, answer, explanation, size);
        } catch (IOException e) {
            LOGGER.error("Failed to write binary query: " + sparql, e);
            throw e;
        }
    }

    @Override
    public synchronized void writeMultiChoiceQuery(String taskType,
                                                   String sparql,
                                                   String predicate,
                                                   String answer,
                                                   String explanation,
                                                   int size) throws IOException {
        if (!initialized || closed) {
            initialize();
        }

        // Skip if we've already processed this SPARQL query
        if (processedQueries.contains(sparql)) {
            return;
        }

        try {
            // Extract the local name from the answer which is typically a URI
            String simplifiedAnswer = cleanEntity(answer);

            // Write basic info to CSV with simplified answer
            csvPrinter.printRecord(taskType, "Multi Choice", sparql, predicate, simplifiedAnswer);
            csvPrinter.flush();

            // Mark as processed
            processedQueries.add(sparql);

            // Extract subject and object based on the query type
            String subject = "";
            String object = "";

            if (sparql.startsWith("SELECT")) {
                if (sparql.contains("SELECT ?subject")) {
                    subject = simplifiedAnswer;
                    // Extract object from the WHERE clause
                    Pattern wherePattern = Pattern.compile("\\?subject\\s+<[^>]+>\\s+<([^>]+)>");
                    Matcher m = wherePattern.matcher(sparql);
                    if (m.find()) {
                        object = cleanEntity(m.group(1));
                    }
                } else if (sparql.contains("SELECT ?object")) {
                    object = simplifiedAnswer;
                    // Extract subject from the WHERE clause
                    Pattern wherePattern = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object");
                    Matcher m = wherePattern.matcher(sparql);
                    if (m.find()) {
                        subject = cleanEntity(m.group(1));
                    }
                } else if (sparql.contains("SELECT ?class")) {
                    object = simplifiedAnswer;
                    // Extract individual from the WHERE clause
                    Pattern wherePattern = Pattern.compile("<([^>]+)>\\s+rdf:type\\s+\\?class");
                    Matcher m = wherePattern.matcher(sparql);
                    if (m.find()) {
                        subject = cleanEntity(m.group(1));
                    }
                }
            }

            // Create a unique triple key for grouping
            String tripleKey = subject + "|" + predicate + "|" + object;

            // Add this query to the set of queries for this triple
            tripleToSparqlQueries.computeIfAbsent(tripleKey, k -> new HashSet<>()).add(sparql);

            // Process and store explanation for JSON
            processExplanation(sparql, tripleKey, taskType, predicate, subject, object, simplifiedAnswer, explanation, size);
        } catch (IOException e) {
            LOGGER.error("Failed to write multi-choice query: " + sparql, e);
            throw e;
        }
    }

    @Override
    public synchronized void writeGroupedMultiChoiceQuery(String taskType,
                                                          String sparql,
                                                          String predicate,
                                                          List<String> answers,
                                                          Map<String, String> explanationMap,
                                                          Map<String, Integer> sizeMap) throws IOException {
        if (!initialized || closed) {
            initialize();
        }

        // Skip if we've already processed this SPARQL query
        if (processedQueries.contains(sparql)) {
            return;
        }

        try {
            // Join all answers with commas for the CSV file
            String combinedAnswers = answers.stream()
                    .map(this::cleanEntity)
                    .collect(Collectors.joining(", "));

            // Write a single row to CSV with all answers combined
            csvPrinter.printRecord(taskType, "Multi Choice", sparql, predicate, combinedAnswers);
            csvPrinter.flush();

            // Mark as processed
            processedQueries.add(sparql);

            // Process each individual answer separately for the JSON output
            for (String answer : answers) {
                String simplifiedAnswer = cleanEntity(answer);
                String explanation = explanationMap.get(answer);
                int size = sizeMap.getOrDefault(answer, 1);

                // Determine subject and object based on the query structure
                String subject = "";
                String object = "";

                if (sparql.startsWith("SELECT")) {
                    if (sparql.contains("SELECT ?subject")) {
                        subject = simplifiedAnswer;
                        // Extract object from the WHERE clause
                        Pattern wherePattern = Pattern.compile("\\?subject\\s+<[^>]+>\\s+<([^>]+)>");
                        Matcher m = wherePattern.matcher(sparql);
                        if (m.find()) {
                            object = cleanEntity(m.group(1));
                        }
                    } else if (sparql.contains("SELECT ?object")) {
                        object = simplifiedAnswer;
                        // Extract subject from the WHERE clause
                        Pattern wherePattern = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object");
                        Matcher m = wherePattern.matcher(sparql);
                        if (m.find()) {
                            subject = cleanEntity(m.group(1));
                        }
                    } else if (sparql.contains("SELECT ?class")) {
                        object = simplifiedAnswer;
                        // Extract individual from the WHERE clause
                        Pattern wherePattern = Pattern.compile("<([^>]+)>\\s+rdf:type\\s+\\?class");
                        Matcher m = wherePattern.matcher(sparql);
                        if (m.find()) {
                            subject = cleanEntity(m.group(1));
                        }
                    }
                }

                // Create a unique triple key for grouping
                String tripleKey = subject + "|" + predicate + "|" + object;

                // Create a unique key for JSON (to avoid conflicts with multiple answers)
                String jsonKey = sparql + "|" + simplifiedAnswer;

                // Add this query to the set of queries for this triple
                tripleToSparqlQueries.computeIfAbsent(tripleKey, k -> new HashSet<>()).add(jsonKey);

                // Process and store explanation for JSON
                processExplanation(jsonKey, tripleKey, taskType, predicate, subject, object, simplifiedAnswer, explanation, size);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write grouped multi-choice query: " + sparql, e);
            throw e;
        }
    }

    private void processExplanation(String sparql,
                                    String tripleKey,
                                    String taskType,
                                    String predicate,
                                    String subject,
                                    String object,
                                    String answer,
                                    String explanation,
                                    int size) {
        // Create JSON structure for this explanation
        ObjectNode explanationNode = jsonMapper.createObjectNode();

        // Create the "inferred" object exactly as specified in requirements
        ObjectNode inferredNode = jsonMapper.createObjectNode();
        inferredNode.put("subject", subject);
        inferredNode.put("predicate", predicate);
        inferredNode.put("object", object);
        explanationNode.set("inferred", inferredNode);

        // Parse explanation text to extract axioms
        ArrayNode explanationsArray = jsonMapper.createArrayNode();

        if (explanation != null && explanation.contains("Directly asserted")) {
            // For directly asserted, create a simple explanation
            ArrayNode axiomArray = jsonMapper.createArrayNode();
            axiomArray.add("Directly asserted");

            // Add to explanations array as a single item
            explanationsArray.add(axiomArray);
        } else {
            // Parse the explanation to extract individual axioms
            List<List<String>> axiomSets = parseExplanationAxioms(explanation, subject, object, predicate);
            if (!axiomSets.isEmpty()) {
                // Filter out empty sets
                axiomSets = axiomSets.stream()
                        .filter(set -> !set.isEmpty())
                        .collect(Collectors.toList());

                // Create explanations array with each set of axioms
                for (List<String> axiomSet : axiomSets) {
                    ArrayNode axiomArray = jsonMapper.createArrayNode();
                    for (String axiom : axiomSet) {
                        axiomArray.add(axiom.trim());
                    }

                    // Only add non-empty arrays
                    if (axiomArray.size() > 0) {
                        explanationsArray.add(axiomArray);
                    }
                }
            }
        }

        // Calculate min and max size from multiple explanations if present
        ObjectNode sizeNode = jsonMapper.createObjectNode();
        if (explanationsArray.size() > 0) {
            int minSize = Integer.MAX_VALUE;
            int maxSize = 0;

            for (int i = 0; i < explanationsArray.size(); i++) {
                int currentSize = explanationsArray.get(i).size();
                minSize = Math.min(minSize, currentSize);
                maxSize = Math.max(maxSize, currentSize);
            }

            if (minSize == Integer.MAX_VALUE) minSize = 0;

            sizeNode.put("min", minSize);
            sizeNode.put("max", maxSize);
        } else {
            sizeNode.put("min", 0);
            sizeNode.put("max", 0);
        }

        // Create the outer structure exactly as specified in requirements
        explanationNode.set("explanations", explanationsArray);
        explanationNode.set("size", sizeNode); // Store size as object with min and max
        explanationNode.put("explanationCount", explanationsArray.size()); // Add count of explanations

        // Store with SPARQL query as key
        synchronized (explanationsMap) {
            explanationsMap.put(sparql, explanationNode);

            // Also store in our triple-based mapping for later consolidation
            tripleExplanationsMap.put(tripleKey, explanationNode);
        }
    }

    private String extractEntity(String text) {
        // Extract entity from text like " <http://example.org/entity> "
        if (text.contains("<") && text.contains(">")) {
            String entity = text.substring(text.indexOf("<") + 1, text.indexOf(">"));
            // Extract the local name from the IRI
            if (entity.contains("/") || entity.contains("#")) {
                return entity.substring(Math.max(entity.lastIndexOf("/"), entity.lastIndexOf("#")) + 1);
            }
            return entity;
        }
        return text.trim();
    }

    private String cleanEntity(String entity) {
        if (entity == null) return "";

        // Clean entity from "<http://example.org/entity>" to "entity"
        // Also handles cases like "http://www.example.com/genealogy.owl#ada_rachel_heath_1868" to "ada_rachel_heath_1868"

        // First, handle full URIs wrapped in angle brackets
        if (entity.startsWith("<") && entity.endsWith(">")) {
            entity = entity.substring(1, entity.length() - 1);
        }

        // Extract local name from URI
        if (entity.contains("#")) {
            // Handle URIs with hash fragments
            return entity.substring(entity.lastIndexOf("#") + 1);
        } else if (entity.contains("/")) {
            // Handle URIs with path segments
            return entity.substring(entity.lastIndexOf("/") + 1);
        }

        return entity;
    }

    private List<List<String>> parseExplanationAxioms(String explanation,
                                                      String subject,
                                                      String object,
                                                      String predicate) {
        List<List<String>> results = new ArrayList<>();

        // If the explanation is empty, return an empty result
        if (explanation == null || explanation.trim().isEmpty()) {
            return results;
        }

        // Check for directly asserted
        if (explanation.contains("Directly asserted")) {
            List<String> directAsserted = new ArrayList<>();
            directAsserted.add("Directly asserted");
            results.add(directAsserted);
            return results;
        }

        // Split by explanation type sections
        Map<String, List<String>> sectionAxioms = new HashMap<>();
        String currentSection = null;
        List<String> currentAxioms = new ArrayList<>();

        for (String line : explanation.split("\n")) {
            line = line.trim();

            // Skip empty lines
            if (line.isEmpty()) continue;

            // Identify section headers
            if (line.equals("Explanations from Pellet:") ||
                    line.equals("Inverse-of:") ||
                    line.equals("Sub-property:") ||
                    line.equals("Equivalent class:") ||
                    line.equals("Subclass:") ||
                    line.equals("Property domain:") ||
                    line.equals("Property range:") ||
                    line.equals("Intermediate class:") ||
                    line.equals("Sub-property path:") ||
                    line.equals("Property chain:") ||
                    line.equals("Inverse property:") ||
                    line.equals("Equivalent property:") ||
                    line.equals("Further explanation of super-property relation:") ||
                    line.equals("Further explanation of inverse relation:") ||
                    line.equals("Further explanation of equivalent relation:") ||
                    line.startsWith("Inverse-of paths:") ||
                    line.startsWith("Sub-property paths:") ||
                    line.startsWith("Property chain paths:") ||
                    line.startsWith("Equivalent property paths:") ||
                    line.startsWith("Equivalent class paths:") ||
                    line.startsWith("Subclass paths:") ||
                    line.startsWith("Property domain/range paths:") ||
                    line.startsWith("Intermediate class paths:")) {

                // Save previous section if any
                if (currentSection != null && !currentAxioms.isEmpty()) {
                    sectionAxioms.put(currentSection, new ArrayList<>(currentAxioms));
                    currentAxioms.clear();
                }

                currentSection = line;

            } else if (line.startsWith("Path ")) {
                // For multi-path explanations, treat each path as a separate section
                if (currentSection != null && !currentAxioms.isEmpty()) {
                    sectionAxioms.put(currentSection + " " + line, new ArrayList<>(currentAxioms));
                    currentAxioms.clear();
                }
            } else if (line.startsWith("-")) {
                // Extract axiom
                String axiom = line.substring(1).trim();

                // Split AND statements
                if (axiom.contains(" AND ")) {
                    String[] parts = axiom.split(" AND ");
                    for (String part : parts) {
                        currentAxioms.add(part.trim());
                    }
                } else {
                    currentAxioms.add(axiom);
                }
            }
        }

        // Save the last section
        if (currentSection != null && !currentAxioms.isEmpty()) {
            sectionAxioms.put(currentSection, new ArrayList<>(currentAxioms));
        }

        // Process each section to create complete explanations
        for (Map.Entry<String, List<String>> entry : sectionAxioms.entrySet()) {
            String section = entry.getKey();
            List<String> axioms = entry.getValue();

            // Create a complete explanation for this section
            List<String> completePath = new ArrayList<>();

            // Create a readable path based on section type
            if (section.contains("Pellet") || section.contains("Path")) {
                // Add all axioms from Pellet or a specific path
                completePath.addAll(axioms);
            } else if (section.contains("Inverse-of") || section.contains("Inverse property")) {
                // For inverse relationships, we need a complete path
                String invProp = null;
                for (String axiom : axioms) {
                    completePath.add(axiom);

                    // Extract the inverse property name
                    if (axiom.contains("InverseOf")) {
                        String[] parts = axiom.split("InverseOf");
                        if (parts.length >= 2) {
                            String prop1 = parts[0].trim();
                            String prop2 = parts[1].trim();

                            // Determine which is the inverse
                            if (prop1.contains(predicate)) {
                                invProp = prop2;
                            } else {
                                invProp = prop1;
                            }
                        }
                    }
                }

                // Add a concrete example if we have the inverse property
                if (invProp != null && !axioms.toString().contains(object + " " + invProp)) {
                    completePath.add(object + " " + invProp + " " + subject);
                }
            } else if (section.contains("Sub-property")) {
                // For subproperty relationships
                String subProp = null;
                for (String axiom : axioms) {
                    completePath.add(axiom);

                    // Extract the subproperty name
                    if (axiom.contains("SubPropertyOf")) {
                        String[] parts = axiom.split("SubPropertyOf");
                        if (parts.length >= 2) {
                            subProp = parts[0].trim();
                        }
                    }
                }

                // Add a concrete example if we have the subproperty
                if (subProp != null && !axioms.toString().contains(subject)) {
                    completePath.add(subject + " " + subProp + " " + object);
                }
            } else if (section.contains("Equivalent class") || section.contains("Subclass") ||
                    section.contains("Equivalent property")) {
                // Add all axioms from class/property hierarchy
                completePath.addAll(axioms);
            } else if (section.startsWith("Further explanation")) {
                // Skip further explanations as they're duplicates
                continue;
            } else {
                // Default case - add all axioms
                completePath.addAll(axioms);
            }

            // Add this complete path if not empty
            if (!completePath.isEmpty()) {
                results.add(completePath);
            }
        }

        // If we have no proper explanations, try to extract from direct text
        if (results.isEmpty()) {
            // Look for specific patterns and create explanations
            List<String> defaultPath = new ArrayList<>();

            // Extract specific property assertions
            if (explanation.contains(subject) && explanation.contains(object)) {
                Pattern pattern = Pattern.compile(subject + "\\s+([\\w]+)\\s+" + object);
                Matcher matcher = pattern.matcher(explanation);
                if (matcher.find()) {
                    defaultPath.add(subject + " " + matcher.group(1) + " " + object);
                }
            }

            // Extract property hierarchies
            Pattern subPropPattern = Pattern.compile("([\\w]+)\\s+SubPropertyOf\\s+([\\w]+)");
            Matcher subPropMatcher = subPropPattern.matcher(explanation);
            while (subPropMatcher.find()) {
                defaultPath.add(subPropMatcher.group(1) + " SubPropertyOf " + subPropMatcher.group(2));
            }

            // Extract inverse properties
            Pattern invPropPattern = Pattern.compile("([\\w]+)\\s+InverseOf\\s+([\\w]+)");
            Matcher invPropMatcher = invPropPattern.matcher(explanation);
            while (invPropMatcher.find()) {
                defaultPath.add(invPropMatcher.group(1) + " InverseOf " + invPropMatcher.group(2));
            }

            // Extract equivalent properties
            Pattern eqPropPattern = Pattern.compile("([\\w]+)\\s+EquivalentTo\\s+([\\w]+)");
            Matcher eqPropMatcher = eqPropPattern.matcher(explanation);
            while (eqPropMatcher.find()) {
                defaultPath.add(eqPropMatcher.group(1) + " EquivalentTo " + eqPropMatcher.group(2));
            }

            // Add default path if we found anything
            if (!defaultPath.isEmpty()) {
                results.add(defaultPath);
            }
        }

        return results;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }

        try {
            // Close CSV file
            if (csvPrinter != null) {
                csvPrinter.close();
                LOGGER.info("CSV file closed: {}", csvFilePath);
            }

            // Create a consolidated JSON with grouped queries
            ObjectNode rootNode = jsonMapper.createObjectNode();

            // First, add all triple-based explanations with their SPARQL queries
            for (Map.Entry<String, Set<String>> entry : tripleToSparqlQueries.entrySet()) {
                String tripleKey = entry.getKey();
                Set<String> sparqlQueries = entry.getValue();

                if (sparqlQueries.size() > 1) {
                    // Multiple queries for the same triple - merge them
                    ObjectNode tripleNode = tripleExplanationsMap.get(tripleKey);
                    if (tripleNode != null) {
                        // Create an array of all SPARQL queries for this triple
                        ArrayNode queriesArray = jsonMapper.createArrayNode();
                        for (String query : sparqlQueries) {
                            // If the query has an answer suffix, remove it
                            if (query.contains("|")) {
                                query = query.substring(0, query.indexOf("|"));
                            }
                            queriesArray.add(query);
                        }

                        // Add the queries array to the triple node
                        tripleNode.set("sparql_queries", queriesArray);

                        // Use the first query as the key in the output
                        String firstQuery = sparqlQueries.iterator().next();
                        if (firstQuery.contains("|")) {
                            firstQuery = firstQuery.substring(0, firstQuery.indexOf("|"));
                        }
                        rootNode.set(firstQuery, tripleNode);
                    }
                } else if (!sparqlQueries.isEmpty()) {
                    // Single query for this triple - use it directly
                    String query = sparqlQueries.iterator().next();
                    ObjectNode explanationNode = null;

                    if (query.contains("|")) {
                        // Handle queries with answer suffixes
                        explanationNode = explanationsMap.get(query);
                        query = query.substring(0, query.indexOf("|"));
                    } else {
                        explanationNode = explanationsMap.get(query);
                    }

                    if (explanationNode != null) {
                        rootNode.set(query, explanationNode);
                    }
                }
            }

            // Write the consolidated JSON to file
            jsonMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(
                            Files.newBufferedWriter(
                                    Path.of(jsonFilePath),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING
                            ),
                            rootNode
                    );

            LOGGER.info("JSON explanations written: {}", jsonFilePath);
            LOGGER.info("Output service closed successfully");

            closed = true;
        } catch (IOException e) {
            LOGGER.error("Error closing output service", e);
            throw e;
        }
    }
}