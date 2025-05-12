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
    private boolean initialized = false;
    private boolean closed = false;

    // Use ConcurrentHashMap for thread safety
    private final Set<String> processedQueries = ConcurrentHashMap.newKeySet();

    // Pattern to extract subject-predicate-object from explanations
    private static final Pattern TRIPLE_PATTERN = Pattern.compile("([\\w\\s]+)\\s+(\\w+)\\s+([\\w\\s]+)");

    public HybridOutputService() {
        this.jsonMapper = new ObjectMapper();
        this.explanationsMap = new HashMap<>();
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

            // Process and store explanation for JSON
            processExplanation(sparql, taskType, predicate, answer, explanation, size);
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

            // Process and store explanation for JSON
            processExplanation(sparql, taskType, predicate, simplifiedAnswer, explanation, size);
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

                // Create a unique key for JSON (append answer to query)
                String jsonKey = sparql + "|" + simplifiedAnswer;

                // Process and store explanation for JSON
                processExplanation(jsonKey, taskType, predicate, simplifiedAnswer, explanation, size);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write grouped multi-choice query: " + sparql, e);
            throw e;
        }
    }

    private void processExplanation(String sparql,
                                    String taskType,
                                    String predicate,
                                    String answer,
                                    String explanation,
                                    int size) {
        // Create JSON structure for this explanation
        ObjectNode explanationNode = jsonMapper.createObjectNode();

        // Extract subject and object from the sparql query
        String subject = "";
        String object = "";

        try {
            if (taskType.equals("Subsumption")) {
                // For Subsumption: ASK { <subClass> rdfs:subClassOf <superClass> }
                if (sparql.contains("subClassOf")) {
                    String[] parts = sparql.split("subClassOf");
                    if (parts.length >= 2) {
                        subject = extractEntity(parts[0]);
                        object = extractEntity(parts[1]);
                    }
                }
            } else if (taskType.equals("Property Assertion")) {
                // For Property Assertion: ASK WHERE { <subject> <property> <object> }
                if (sparql.contains("{") && sparql.contains("}")) {
                    String[] parts = sparql.split("\\{|\\}")[1].split("\\s+");
                    if (parts.length >= 4) {
                        subject = cleanEntity(parts[1]);
                        object = cleanEntity(parts[3]);
                    }
                }
            } else if (taskType.equals("Membership")) {
                // For Membership: ASK WHERE { <individual> rdf:type <class> }
                if (sparql.contains("{") && sparql.contains("}")) {
                    String[] parts = sparql.split("\\{|\\}")[1].split("\\s+");
                    if (parts.length >= 4 && parts[2].contains("type")) {
                        subject = cleanEntity(parts[1]);
                        object = cleanEntity(parts[3]);
                    }
                }
            }

            // For Multi Choice queries with "SELECT ?subject" or "SELECT ?object",
            // the answer may contain multiple entity names
            if (sparql.startsWith("SELECT")) {
                // Check if we're dealing with a multi-choice query
                if (sparql.contains("SELECT ?subject")) {
                    subject = answer; // Answer already contains short names
                } else if (sparql.contains("SELECT ?object")) {
                    object = answer;  // Answer already contains short names
                } else if (sparql.contains("SELECT ?class")) {
                    object = answer;  // For membership queries, the answer is the class (object)
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error parsing SPARQL query: " + sparql, e);
        }

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

        // Store with SPARQL query as key
        synchronized (explanationsMap) {
            explanationsMap.put(sparql, explanationNode);
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
                    line.equals("Further explanation of equivalent relation:")) {

                // Save previous section if any
                if (currentSection != null && !currentAxioms.isEmpty()) {
                    sectionAxioms.put(currentSection, new ArrayList<>(currentAxioms));
                    currentAxioms.clear();
                }

                currentSection = line;

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
            if (section.equals("Explanations from Pellet:")) {
                // Add all axioms from Pellet
                completePath.addAll(axioms);
            } else if (section.equals("Inverse-of:") || section.equals("Inverse property:")) {
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
            } else if (section.equals("Sub-property:") || section.equals("Sub-property path:")) {
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
            } else if (section.equals("Equivalent class:") || section.equals("Subclass:") ||
                    section.equals("Equivalent property:")) {
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

            // Write JSON file
            Map<String, ObjectNode> uniqueJsonEntries = new HashMap<>();
            synchronized (explanationsMap) {
                // Consolidate JSON entries by base SPARQL query
                for (Map.Entry<String, ObjectNode> entry : explanationsMap.entrySet()) {
                    String key = entry.getKey();
                    // Extract base SPARQL query if it contains pipe separator
                    if (key.contains("|")) {
                        key = key.substring(0, key.indexOf("|"));
                    }
                    // Use the most recent entry for each unique SPARQL query
                    uniqueJsonEntries.put(key, entry.getValue());
                }
            }

            ObjectNode rootNode = jsonMapper.createObjectNode();
            for (Map.Entry<String, ObjectNode> entry : uniqueJsonEntries.entrySet()) {
                rootNode.set(entry.getKey(), entry.getValue());
            }

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