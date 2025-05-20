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
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class HybridOutputService implements OutputService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HybridOutputService.class);

    private CSVPrinter csvPrinter;
    private final ObjectMapper jsonMapper;
    private final Map<String, ObjectNode> explanationsMap;
    private final Map<String, Set<String>> tripleToSparqlQueries;
    private final Map<String, ObjectNode> tripleExplanationsMap;

    // track which root entity each triple came from
    private final Map<String, String> tripleKeyToRoot = new HashMap<>();

    // current file base-name
    private String currentRootEntity;

    private final Set<String> processedQueries = ConcurrentHashMap.newKeySet();
    private boolean initialized = false;
    private boolean closed = false;

    // Pattern to extract subject-predicate-object from explanations
    private static final Pattern TRIPLE_PATTERN = Pattern.compile("([\\w\\s]+)\\s+(\\w+)\\s+([\\w\\s]+)");

    public HybridOutputService() {
        this.jsonMapper = new ObjectMapper();
        this.explanationsMap = new HashMap<>();
        this.tripleToSparqlQueries = new HashMap<>();
        this.tripleExplanationsMap = new HashMap<>();
        LOGGER.info("Hybrid output service created");
    }

    /**
     * Called by the runner before each ontology to set the “root entity” name.
     */
    public synchronized void setCurrentRootEntity(String rootEntity) {
        this.currentRootEntity = rootEntity;
    }

    @Value("${output.csv.path:SPARQL_questions.csv}")
    private String csvFilePath;

    @Value("${output.json.path:explanations.json}")
    private String jsonFilePath;

    @Override
    public synchronized void initialize() throws IOException {
        if (initialized || closed) return;

        LOGGER.info("Initializing Hybrid output service with CSV: {}, JSON: {}", csvFilePath, jsonFilePath);

        Path csvPath = Path.of(csvFilePath);
        Path jsonPath = Path.of(jsonFilePath);
        Files.createDirectories(csvPath.getParent() != null ? csvPath.getParent() : Path.of("."));
        Files.createDirectories(jsonPath.getParent() != null ? jsonPath.getParent() : Path.of("."));

        boolean fileExists = Files.exists(csvPath) && Files.size(csvPath) > 0;

        BufferedWriter csvWriter = Files.newBufferedWriter(
                csvPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );

        CSVFormat csvFormat = CSVFormat.DEFAULT
                .withQuoteMode(QuoteMode.ALL)
                .withEscape('\\')
                .withRecordSeparator("\n");

        // Only include header if the file is new or empty
        if (!fileExists) {
            csvFormat = csvFormat.withHeader(
                    "Task ID",
                    "Root Entity",
                    "Size of ontology TBox",
                    "Size of ontology ABox",
                    "Task Type",
                    "Answer Type",
                    "SPARQL Query",
                    "Predicate",
                    "Answer"
            );
        }

        this.csvPrinter = new CSVPrinter(csvWriter, csvFormat);

        initialized = true;
        LOGGER.info("Hybrid output service initialized");
    }

    private int tboxSize = 0;
    private int aboxSize = 0;

    /**
     * Set the TBox and ABox sizes for the current ontology
     */
    public synchronized void setOntologySizes(int tboxSize, int aboxSize) {
        this.tboxSize = tboxSize;
        this.aboxSize = aboxSize;
        LOGGER.info("Set ontology sizes - TBox: {}, ABox: {}", tboxSize, aboxSize);
    }

    /**
     * Reset processed queries tracking to start fresh with a new ontology
     */
    public synchronized void resetProcessedQueries() {
        LOGGER.info("Resetting processed queries tracking");
        processedQueries.clear();
    }

    /**
     * Generate a task ID based on the specified format
     */
    private String generateTaskId(String subject, String predicate, String answerType) {
        // Abbreviate the subject to first part (before first underscore if present)
        String shortSubject = subject;
        int underscoreIndex = subject.indexOf('_');
        if (underscoreIndex > 0) {
            shortSubject = subject.substring(0, underscoreIndex);
        }

        // Abbreviate the answer type
        String shortAnswerType = answerType.equals("Binary") ? "BIN" : "MC";

        // Create the task ID: 1hop-rootEntity-subject-predicate-answerType
        return String.format("1hop-%s-%s-%s-%s",
                currentRootEntity,
                shortSubject,
                predicate,
                shortAnswerType);
    }

    @Override
    public synchronized void writeBinaryQuery(String taskType,
                                              String sparql,
                                              String predicate,
                                              String answer,
                                              String explanation,
                                              int size) throws IOException {
        if (!initialized || closed) initialize();

        if (processedQueries.contains(sparql)) return;

        // extract subject/object, build tripleKey...
        String subject = "", object = "";
        if (taskType.equals("Subsumption") && sparql.contains("subClassOf")) {
            String[] parts = sparql.split("subClassOf");
            subject = extractEntity(parts[0]);
            object = extractEntity(parts[1]);
        } else if (taskType.equals("Property Assertion")) {
            String[] parts = sparql.split("\\{|\\}")[1].split("\\s+");
            subject = cleanEntity(parts[1]);
            object = cleanEntity(parts[3]);
        } else if (taskType.equals("Membership")) {
            String[] parts = sparql.split("\\{|\\}")[1].split("\\s+");
            subject = cleanEntity(parts[1]);
            object = cleanEntity(parts[3]);
        }

        // Convert to object-only query if necessary
        String objectOnlyQuery = sparql;
        if (taskType.equals("Property Assertion") && !sparql.contains("?object")) {
            objectOnlyQuery = "SELECT ?object WHERE { <" + subject + "> <" + predicate + "> ?object }";
        }

        // Generate task ID
        String taskId = generateTaskId(subject, predicate, "Binary");

        // Write to CSV with new columns
        csvPrinter.printRecord(
                taskId,
                currentRootEntity,
                tboxSize,
                aboxSize,
                taskType,
                "Binary",
                objectOnlyQuery,
                predicate,
                answer
        );
        csvPrinter.flush();
        processedQueries.add(sparql);

        String tripleKey = subject + "|" + predicate + "|" + object;
        tripleToSparqlQueries
                .computeIfAbsent(tripleKey, k -> new HashSet<>())
                .add(sparql);

        // Record which file this triple belongs to
        tripleKeyToRoot.put(tripleKey, currentRootEntity);

        processExplanation(
                sparql, tripleKey, taskType,
                predicate, subject, object,
                answer, explanation, size
        );
    }

    @Override
    public synchronized void writeMultiChoiceQuery(String taskType,
                                                   String sparql,
                                                   String predicate,
                                                   String answer,
                                                   String explanation,
                                                   int size) throws IOException {
        if (!initialized || closed) initialize();
        if (processedQueries.contains(sparql)) return;

        String simplifiedAnswer = cleanEntity(answer);

        // extract subject/object similarly...
        String subject = "", object = "";
        if (sparql.startsWith("SELECT")) {
            if (sparql.contains("SELECT ?subject")) {
                subject = simplifiedAnswer;
                Matcher m = Pattern.compile("\\?subject\\s+<[^>]+>\\s+<([^>]+)>")
                        .matcher(sparql);
                if (m.find()) object = cleanEntity(m.group(1));

                // Convert to object-only query
                sparql = "SELECT ?object WHERE { <" + subject + "> <" + predicate + "> ?object }";
            } else if (sparql.contains("SELECT ?object")) {
                object = simplifiedAnswer;
                Matcher m = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object")
                        .matcher(sparql);
                if (m.find()) subject = cleanEntity(m.group(1));
            } else if (sparql.contains("SELECT ?class")) {
                object = simplifiedAnswer;
                Matcher m = Pattern.compile("<([^>]+)>\\s+rdf:type\\s+\\?class")
                        .matcher(sparql);
                if (m.find()) subject = cleanEntity(m.group(1));
            }
        }

        // Generate task ID
        String taskId = generateTaskId(subject, predicate, "Multi Choice");

        csvPrinter.printRecord(
                taskId,
                currentRootEntity,
                tboxSize,
                aboxSize,
                taskType,
                "Multi Choice",
                sparql,
                predicate,
                simplifiedAnswer
        );
        csvPrinter.flush();
        processedQueries.add(sparql);

        String tripleKey = subject + "|" + predicate + "|" + object;
        tripleToSparqlQueries
                .computeIfAbsent(tripleKey, k -> new HashSet<>())
                .add(sparql);

        // NEW
        tripleKeyToRoot.put(tripleKey, currentRootEntity);

        processExplanation(
                sparql, tripleKey, taskType,
                predicate, subject, object,
                simplifiedAnswer, explanation, size
        );
    }

    @Override
    public synchronized void writeGroupedMultiChoiceQuery(String taskType,
                                                          String sparql,
                                                          String predicate,
                                                          List<String> answers,
                                                          Map<String, String> explanationMap,
                                                          Map<String, Integer> sizeMap) throws IOException {
        if (!initialized || closed) initialize();
        if (processedQueries.contains(sparql)) return;

        String combined = answers.stream()
                .map(this::cleanEntity)
                .collect(Collectors.joining(", "));

        // Extract subject for task ID
        String subject = "";
        if (sparql.startsWith("SELECT")) {
            if (sparql.contains("SELECT ?subject")) {
                // Need to get any of the answers as a subject
                if (!answers.isEmpty()) {
                    subject = cleanEntity(answers.get(0));
                }

                // Convert to object-only query
                if (!subject.isEmpty()) {
                    sparql = "SELECT ?object WHERE { <" + subject + "> <" + predicate + "> ?object }";
                }
            } else if (sparql.contains("SELECT ?object")) {
                Matcher m = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object")
                        .matcher(sparql);
                if (m.find()) subject = cleanEntity(m.group(1));
            } else if (sparql.contains("SELECT ?class")) {
                Matcher m = Pattern.compile("<([^>]+)>\\s+rdf:type\\s+\\?class")
                        .matcher(sparql);
                if (m.find()) subject = cleanEntity(m.group(1));
            }
        }

        // Generate task ID
        String taskId = generateTaskId(subject, predicate, "Multi Choice");

        csvPrinter.printRecord(
                taskId,
                currentRootEntity,
                tboxSize,
                aboxSize,
                taskType,
                "Multi Choice",
                sparql,
                predicate,
                combined
        );
        csvPrinter.flush();
        processedQueries.add(sparql);

        for (String answer : answers) {
            String simp = cleanEntity(answer);
            int sz = sizeMap.getOrDefault(answer, 1);
            String expl = explanationMap.get(answer);

            String object = "";
            if (sparql.startsWith("SELECT")) {
                if (sparql.contains("SELECT ?subject")) {
                    subject = simp;
                    Matcher m = Pattern.compile("\\?subject\\s+<[^>]+>\\s+<([^>]+)>")
                            .matcher(sparql);
                    if (m.find()) object = cleanEntity(m.group(1));
                } else if (sparql.contains("SELECT ?object")) {
                    object = simp;
                    Matcher m = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object")
                            .matcher(sparql);
                    if (m.find()) subject = cleanEntity(m.group(1));
                } else if (sparql.contains("SELECT ?class")) {
                    object = simp;
                    Matcher m = Pattern.compile("<([^>]+)>\\s+rdf:type\\s+\\?class")
                            .matcher(sparql);
                    if (m.find()) subject = cleanEntity(m.group(1));
                }
            }

            String tripleKey = subject + "|" + predicate + "|" + object;
            String jsonKey = sparql + "|" + simp;

            tripleToSparqlQueries
                    .computeIfAbsent(tripleKey, k -> new HashSet<>())
                    .add(jsonKey);

            tripleKeyToRoot.put(tripleKey, currentRootEntity);

            processExplanation(
                    jsonKey, tripleKey, taskType,
                    predicate, subject, object,
                    simp, expl, sz
            );
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
        ObjectNode explanationNode = jsonMapper.createObjectNode();

        ObjectNode inferredNode = jsonMapper.createObjectNode();
        inferredNode.put("subject", subject);
        inferredNode.put("predicate", predicate);
        inferredNode.put("object", object);
        explanationNode.set("inferred", inferredNode);

        ArrayNode explanationsArray = jsonMapper.createArrayNode();
        if (explanation != null && explanation.contains("Directly asserted")) {
            ArrayNode axiomArray = jsonMapper.createArrayNode().add("Directly asserted");
            explanationsArray.add(axiomArray);
        } else {
            List<List<String>> axSets = parseExplanationAxioms(explanation, subject, object, predicate);
            for (List<String> set : axSets) {
                ArrayNode arr = jsonMapper.createArrayNode();
                set.forEach(arr::add);
                if (arr.size() > 0) explanationsArray.add(arr);
            }
        }

        ObjectNode sizeNode = jsonMapper.createObjectNode();
        if (explanationsArray.size() > 0) {
            int min = Integer.MAX_VALUE, max = 0;
            for (int i = 0; i < explanationsArray.size(); i++) {
                int c = explanationsArray.get(i).size();
                min = Math.min(min, c);
                max = Math.max(max, c);
            }
            if (min == Integer.MAX_VALUE) min = 0;
            sizeNode.put("min", min);
            sizeNode.put("max", max);
        } else {
            sizeNode.put("min", 0);
            sizeNode.put("max", 0);
        }

        explanationNode.set("explanations", explanationsArray);
        explanationNode.set("size", sizeNode);
        explanationNode.put("explanationCount", explanationsArray.size());

        synchronized (explanationsMap) {
            explanationsMap.put(sparql, explanationNode);
            tripleExplanationsMap.put(tripleKey, explanationNode);
        }
    }

    private String extractEntity(String text) {
        // Extract entity from text
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
        if (closed) return;

        if (csvPrinter != null) {
            csvPrinter.close();
            LOGGER.info("CSV file closed: {}", csvFilePath);
        }

        // Build JSON from current session
        ObjectNode newRootNode = jsonMapper.createObjectNode();
        for (Map.Entry<String, Set<String>> entry : tripleToSparqlQueries.entrySet()) {
            String tripleKey = entry.getKey();
            String root = tripleKeyToRoot.getOrDefault(tripleKey, "UNKNOWN");

            ObjectNode fileNode = newRootNode.has(root)
                    ? (ObjectNode) newRootNode.get(root)
                    : jsonMapper.createObjectNode();
            newRootNode.set(root, fileNode);

            String firstQuery = entry.getValue().iterator().next();
            if (firstQuery.contains("|")) {
                firstQuery = firstQuery.substring(0, firstQuery.indexOf("|"));
            }
            ObjectNode explanationNode = tripleExplanationsMap.get(tripleKey);
            fileNode.set(firstQuery, explanationNode);
        }

        // Debug output to track what's happening with the JSON merging
        LOGGER.info("New root node has {} root entities", newRootNode.size());
        StringBuilder newRootEntities = new StringBuilder("New root entities: ");
        Iterator<String> fieldNames = newRootNode.fieldNames();
        while (fieldNames.hasNext()) {
            newRootEntities.append(fieldNames.next()).append(", ");
        }
        LOGGER.info(newRootEntities.toString());

        // Read existing JSON file if it exists
        Path jsonPath = Path.of(jsonFilePath);
        ObjectNode existingRootNode = jsonMapper.createObjectNode();
        if (Files.exists(jsonPath) && Files.size(jsonPath) > 0) {
            try {
                existingRootNode = (ObjectNode) jsonMapper.readTree(jsonPath.toFile());
                LOGGER.info("Existing JSON file loaded with {} root entities", existingRootNode.size());

                StringBuilder existingRootEntities = new StringBuilder("Existing root entities: ");
                Iterator<String> existingFields = existingRootNode.fieldNames();
                while (existingFields.hasNext()) {
                    existingRootEntities.append(existingFields.next()).append(", ");
                }
                LOGGER.info(existingRootEntities.toString());
            } catch (IOException e) {
                LOGGER.warn("Failed to read existing JSON file, will create a new one", e);
            }
        } else {
            LOGGER.info("No existing JSON file found or file is empty");
        }

        // Merge existing and new data - fixing the lambda issues
        LOGGER.info("Starting merge of JSON data");
        Iterator<Map.Entry<String, JsonNode>> fields = newRootNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String rootEntity = entry.getKey();
            ObjectNode rootContent = (ObjectNode) entry.getValue();

            LOGGER.info("Processing root entity: {}", rootEntity);

            if (existingRootNode.has(rootEntity)) {
                LOGGER.info("Found existing data for entity {}", rootEntity);
                // Merge with existing entity data
                final ObjectNode existingContent = (ObjectNode) existingRootNode.get(rootEntity);
                Iterator<Map.Entry<String, JsonNode>> contentFields = rootContent.fields();

                while (contentFields.hasNext()) {
                    Map.Entry<String, JsonNode> field = contentFields.next();
                    LOGGER.info("  Adding query: {}", field.getKey());
                    existingContent.set(field.getKey(), field.getValue());
                }
            } else {
                LOGGER.info("Adding new entity: {}", rootEntity);
                // Add new entity data
                existingRootNode.set(rootEntity, rootContent);
            }
        }

        LOGGER.info("Final merged JSON has {} root entities", existingRootNode.size());

        // Write the merged JSON
        try (BufferedWriter writer = Files.newBufferedWriter(
                jsonPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            jsonMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(writer, existingRootNode);
        }

        LOGGER.info("JSON explanations written: {}", jsonFilePath);
        closed = true;
    }
}
