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
import com.example.tracking.GlobalQueryTracker;
import com.example.explanation.ExplanationTagger;

@Component
public class HybridOutputService implements OutputService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HybridOutputService.class);

    private CSVPrinter csvPrinter;
    private final ObjectMapper jsonMapper;
    private final Map<String, ObjectNode> explanationsMap;
    private final Map<String, Set<String>> tripleToSparqlQueries;
    private final Map<String, ObjectNode> tripleExplanationsMap;
    private final Map<String, Set<String>> tripleToTaskIds = new HashMap<>();
    
    // Tagging service for complexity analysis
    private final ExplanationTagger explanationTagger;

    // NEW: Memory optimization constants
    private static final int MAX_CROSS_REFERENCES = 1000;
    private static final int MAX_TASK_IDS_PER_TRIPLE = 50;
    private static final int MAX_EXPLANATION_PATHS = 10;
    private static final int MAX_AXIOMS_PER_PATH = 20;
    private static final int MAX_SPARQL_QUERIES_PER_TRIPLE = 20;

    // track which root entity each triple came from
    private final Map<String, String> tripleKeyToRoot = new HashMap<>();

    // current file base-name
    private String currentRootEntity;

    private boolean initialized = false;
    private boolean closed = false;

    // Pattern to extract subject-predicate-object from explanations
    private static final Pattern TRIPLE_PATTERN = Pattern.compile("([\\w\\s]+)\\s+(\\w+)\\s+([\\w\\s]+)");

    public HybridOutputService() {
        this.jsonMapper = new ObjectMapper();
        this.explanationsMap = new HashMap<>();
        this.tripleToSparqlQueries = new HashMap<>();
        this.tripleExplanationsMap = new HashMap<>();
        this.explanationTagger = new ExplanationTagger();
        LOGGER.info("Hybrid output service created");
    }

    private void logMemoryStats() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        LOGGER.info("Memory usage: {} MB used, {} MB max",
                usedMemory / 1024 / 1024, runtime.maxMemory() / 1024 / 1024);
    }

    /**
     * Called by the runner before each ontology to set the "root entity" name.
     */
    public synchronized void setCurrentRootEntity(String rootEntity) {
        this.currentRootEntity = rootEntity;
    }

    @Value("${output.csv.path:SPARQL_questions_2hop.csv}")
    private String csvFilePath;

    @Value("${output.json.path:explanations_2hop.json}")
    private String jsonFilePath;

    @Override
    public synchronized void initialize() throws IOException {
        if (initialized || closed) return;

        LOGGER.info("Initializing Hybrid output service with CSV: {}, JSON: {}", csvFilePath, jsonFilePath);

        // Log memory info
        Runtime runtime = Runtime.getRuntime();
        LOGGER.info("Max memory: {} MB", runtime.maxMemory() / 1024 / 1024);
        LOGGER.info("Total memory: {} MB", runtime.totalMemory() / 1024 / 1024);

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

        // Updated header with explanation statistics
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
                    "Answer",
                    "Avg Min Explanation Size",
                    "Avg Max Explanation Size",
                    "Avg Explanation Count"
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
     * Generate a task ID based on the specified format
     */
    private String generateTaskId(String subject, String predicate, String answerType) {
        // Use short names consistently - fix the extraction
        String shortSubject = extractShortName(subject);
        String shortPredicate = extractShortName(predicate);

        // Abbreviate the subject to first part (before first underscore if present)
        int underscoreIndex = shortSubject.indexOf('_');
        if (underscoreIndex > 0) {
            shortSubject = shortSubject.substring(0, underscoreIndex);
        }

        // Abbreviate the answer type
        String shortAnswerType = answerType.equals("Binary") ? "BIN" : "MC";

        return String.format("1hop-%s-%s-%s-%s",
                currentRootEntity, shortSubject, shortPredicate, shortAnswerType);
    }

    private String extractFullIRI(String sparql, String pattern) {
        Pattern p = Pattern.compile("<([^>]+)>");
        Matcher m = p.matcher(sparql);
        if (m.find()) {
            return "<" + m.group(1) + ">";
        }
        return sparql;
    }

    private String createNormalizedTripleKey(String subject, String predicate, String object) {
        return extractShortName(subject) + "|" +
                extractShortName(predicate) + "|" +
                extractShortName(object);
    }

    private String normalizeIRIForKey(String iri) {
        if (iri == null || iri.isEmpty()) return "";

        // Ensure consistent format with angle brackets
        if (!iri.startsWith("<") && (iri.startsWith("http://") || iri.startsWith("https://"))) {
            return "<" + iri + ">";
        }

        return iri;
    }


    @Override
    public synchronized void writeBinaryQuery(String taskType,
                                              String sparql,
                                              String predicate,
                                              String answer,
                                              String explanation,
                                              int size) throws IOException {
        if (!initialized || closed) initialize();

        // Extract entities from normalized SPARQL (much simpler now)
        String subject = extractSubjectFromSparql(sparql);
        String object = extractObjectFromSparql(sparql);

        // Generate task ID using short names for readability
        String taskId = generateTaskId(
                extractShortName(subject),
                predicate,
                "Binary"
        );

        // Create triple key using full IRIs for consistency
        String tripleKey = createNormalizedTripleKey(subject, predicate, object);

        // Store task ID
        tripleToTaskIds.computeIfAbsent(tripleKey, k -> new HashSet<>()).add(taskId);
        tripleToSparqlQueries.computeIfAbsent(tripleKey, k -> new HashSet<>()).add(sparql);
        tripleKeyToRoot.put(tripleKey, currentRootEntity);

        // Process explanation
        processExplanation(sparql, tripleKey, taskType, predicate,
                extractShortName(subject), extractShortName(object),
                answer, explanation, size);

        // Calculate stats and write to CSV
        ExplanationStats stats = calculateExplanationStats(sparql, tripleKey);

        csvPrinter.printRecord(
                taskId, currentRootEntity, tboxSize, aboxSize,
                taskType, "Binary", sparql, predicate, answer,
                Math.round(stats.avgMin), Math.round(stats.avgMax), Math.round(stats.avgCount)
        );
        csvPrinter.flush();
    }

    // ADD these helper methods:
    private String extractSubjectFromSparql(String sparql) {
        Pattern pattern = Pattern.compile("\\{\\s*(<[^>]+>)");
        Matcher matcher = pattern.matcher(sparql);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractObjectFromSparql(String sparql) {
        Pattern pattern = Pattern.compile("(<[^>]+>)\\s*\\}");
        Matcher matcher = pattern.matcher(sparql);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractShortName(String fullIRI) {
        if (fullIRI == null || fullIRI.isEmpty()) {
            return fullIRI;
        }

        // Handle angle bracket IRIs
        if (fullIRI.startsWith("<") && fullIRI.endsWith(">")) {
            String iri = fullIRI.substring(1, fullIRI.length() - 1);
            if (iri.contains("#")) {
                return iri.substring(iri.lastIndexOf("#") + 1);
            } else if (iri.contains("/")) {
                return iri.substring(iri.lastIndexOf("/") + 1);
            }
            return iri;
        }

        // Handle non-bracketed IRIs
        if (fullIRI.startsWith("http://") || fullIRI.startsWith("https://")) {
            if (fullIRI.contains("#")) {
                return fullIRI.substring(fullIRI.lastIndexOf("#") + 1);
            } else if (fullIRI.contains("/")) {
                return fullIRI.substring(fullIRI.lastIndexOf("/") + 1);
            }
        }

        return fullIRI;
    }

    private String normalizeSparqlQuery(String sparql) {
        return sparql;
    }

    @Override
    public synchronized void writeMultiChoiceQuery(String taskType,
                                                   String sparql,
                                                   String predicate,
                                                   String answer,
                                                   String explanation,
                                                   int size) throws IOException {
        if (!initialized || closed) initialize();
        String simplifiedAnswer = extractShortName(answer);

        // extract subject/object similarly...
        String subject = "", object = "";
        if (sparql.startsWith("SELECT")) {
            if (sparql.contains("SELECT ?subject")) {
                subject = simplifiedAnswer;
                Matcher m = Pattern.compile("\\?subject\\s+<[^>]+>\\s+<([^>]+)>")
                        .matcher(sparql);
                if (m.find()) object = extractShortName(m.group(1));

                // Convert to object-only query
                sparql = "SELECT ?object WHERE { <" + subject + "> <" + predicate + "> ?object }";
            } else if (sparql.contains("SELECT ?object")) {
                object = simplifiedAnswer;
                Matcher m = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object")
                        .matcher(sparql);
                if (m.find()) subject = extractShortName(m.group(1));
            } else if (sparql.contains("SELECT ?class")) {
                object = simplifiedAnswer;
                Matcher m = Pattern.compile("<([^>]+)>\\s+rdf:type\\s+\\?class")
                        .matcher(sparql);
                if (m.find()) subject = extractShortName(m.group(1));
            }
        }

        // Generate task ID
        String taskId = generateTaskId(subject, predicate, "Multi Choice");

        // Store task ID for this triple
        String tripleKey = createNormalizedTripleKey(subject, predicate, object);
        tripleToTaskIds.computeIfAbsent(tripleKey, k -> new HashSet<>()).add(taskId);

        tripleToSparqlQueries
                .computeIfAbsent(tripleKey, k -> new HashSet<>())
                .add(sparql);

        tripleKeyToRoot.put(tripleKey, currentRootEntity);

        // Process explanation first
        processExplanation(
                sparql, tripleKey, taskType,
                predicate, subject, object,
                simplifiedAnswer, explanation, size
        );

        // Calculate explanation statistics
        ExplanationStats stats = calculateExplanationStats(sparql, tripleKey);

        csvPrinter.printRecord(
                taskId,
                currentRootEntity,
                tboxSize,
                aboxSize,
                taskType,
                "Multi Choice",
                sparql,
                predicate,
                simplifiedAnswer,
                Math.round(stats.avgMin),
                Math.round(stats.avgMax),
                Math.round(stats.avgCount)
        );
        csvPrinter.flush();
    }

    @Override
    public synchronized void writeGroupedMultiChoiceQuery(String taskType,
                                                          String sparql,
                                                          String predicate,
                                                          List<String> answers,
                                                          Map<String, String> explanationMap,
                                                          Map<String, Integer> sizeMap) throws IOException {
        if (!initialized || closed) initialize();

        String combined = answers.stream()
                .map(this::extractShortName)
                .collect(Collectors.joining(", "));

        // Extract subject for task ID
        String baseSubject = "";
        if (sparql.startsWith("SELECT")) {
            if (sparql.contains("SELECT ?subject")) {
                if (!answers.isEmpty()) {
                    baseSubject = extractShortName(answers.get(0));
                }
                if (!baseSubject.isEmpty()) {
                    sparql = "SELECT ?object WHERE { <" + baseSubject + "> <" + predicate + "> ?object }";
                }
            } else if (sparql.contains("SELECT ?object")) {
                Matcher m = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object")
                        .matcher(sparql);
                if (m.find()) baseSubject = extractShortName(m.group(1));
            } else if (sparql.contains("SELECT ?class")) {
                Matcher m = Pattern.compile("<([^>]+)>\\s+<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>\\s+\\?class")
                        .matcher(sparql);
                if (m.find()) {
                    baseSubject = extractShortName(m.group(1));
                } else {
                    // Fallback for different rdf:type syntax
                    m = Pattern.compile("<([^>]+)>\\s+a\\s+\\?class").matcher(sparql);
                    if (m.find()) {
                        baseSubject = extractShortName(m.group(1));
                    }
                }
            }
        }

        // Generate task ID
        String taskId = generateTaskId(baseSubject, predicate, "Multi Choice");

        // Process all explanations first
        List<String> allTripleKeys = new ArrayList<>();
        for (String answer : answers) {
            String simp = extractShortName(answer);
            int sz = sizeMap.getOrDefault(answer, 1);
            String expl = explanationMap.get(answer);

            String subject = baseSubject;
            String object = "";

            if (sparql.startsWith("SELECT")) {
                if (sparql.contains("SELECT ?subject")) {
                    subject = simp;
                    Matcher m = Pattern.compile("\\?subject\\s+<[^>]+>\\s+<([^>]+)>")
                            .matcher(sparql);
                    if (m.find()) object = extractShortName(m.group(1));
                } else if (sparql.contains("SELECT ?object")) {
                    object = simp;
                    Matcher m = Pattern.compile("<([^>]+)>\\s+<[^>]+>\\s+\\?object")
                            .matcher(sparql);
                    if (m.find()) subject = extractShortName(m.group(1));
                } else if (sparql.contains("SELECT ?class")) {
                    object = simp;
                    Matcher m = Pattern.compile("<([^>]+)>\\s+<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>\\s+\\?class")
                            .matcher(sparql);
                    if (m.find()) {
                        subject = extractShortName(m.group(1));
                    } else {
                        m = Pattern.compile("<([^>]+)>\\s+a\\s+\\?class").matcher(sparql);
                        if (m.find()) {
                            subject = extractShortName(m.group(1));
                        }
                    }
                }
            }

            String tripleKey = createNormalizedTripleKey(subject, predicate, object);
            String jsonKey = sparql + "|" + simp;
            allTripleKeys.add(tripleKey);

            tripleToTaskIds.computeIfAbsent(tripleKey, k -> new HashSet<>()).add(taskId);
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

        // Calculate average explanation statistics across all related triples
        List<Double> allMinSizes = new ArrayList<>();
        List<Double> allMaxSizes = new ArrayList<>();
        List<Double> allCounts = new ArrayList<>();

        for (String tripleKey : allTripleKeys) {
            ExplanationStats stats = calculateExplanationStats(sparql, tripleKey);
            allMinSizes.add(stats.avgMin);
            allMaxSizes.add(stats.avgMax);
            allCounts.add(stats.avgCount);
        }

        double overallAvgMin = allMinSizes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double overallAvgMax = allMaxSizes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double overallAvgCount = allCounts.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        csvPrinter.printRecord(
                taskId,
                currentRootEntity,
                tboxSize,
                aboxSize,
                taskType,
                "Multi Choice",
                sparql,
                predicate,
                combined,
                Math.round(overallAvgMin),
                Math.round(overallAvgMax),
                Math.round(overallAvgCount)
        );
        csvPrinter.flush();
    }

    // OPTIMIZED: Memory-efficient cross-referencing
    private void updateExplanationsWithCrossReferences() {
        LOGGER.info("Updating explanations with cross-referenced task IDs (memory-optimized)");

        int processedCount = 0;
        for (Map.Entry<String, ObjectNode> entry : tripleExplanationsMap.entrySet()) {
            String tripleKey = entry.getKey();
            ObjectNode explanationNode = entry.getValue();

            // Get task IDs for this specific triple only (simplified cross-referencing)
            Set<String> taskIds = tripleToTaskIds.getOrDefault(tripleKey, new HashSet<>());

            // Limit task IDs to prevent memory issues
            if (taskIds.size() > MAX_TASK_IDS_PER_TRIPLE) {
                taskIds = taskIds.stream()
                        .limit(MAX_TASK_IDS_PER_TRIPLE)
                        .collect(Collectors.toSet());
                LOGGER.warn("Limited task IDs for triple {} to {} entries", tripleKey, MAX_TASK_IDS_PER_TRIPLE);
            }

            // Update the explanation with task IDs
            ArrayNode taskIdsArray = jsonMapper.createArrayNode();
            taskIds.stream()
                    .sorted()
                    .forEach(taskIdsArray::add);
            explanationNode.set("taskIds", taskIdsArray);

            processedCount++;
            if (processedCount % 100 == 0) {
                LOGGER.info("Processed {} explanations", processedCount);
            }
        }

        LOGGER.info("Completed updating {} explanations", processedCount);
    }

    // OPTIMIZED: Memory-efficient explanation processing
    private void processExplanation(String sparql,
                                    String tripleKey,
                                    String taskType,
                                    String predicate,
                                    String subject,
                                    String object,
                                    String answer,
                                    String explanation,
                                    int size) {
        // Check if we already have an explanation for this triple
        ObjectNode existingNode = tripleExplanationsMap.get(tripleKey);
        if (existingNode != null) {
            // If we already have an explanation, just update the task IDs and SPARQL queries
            // but don't recreate the explanation
            return;
        }

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
            // OPTIMIZED: Limit number of explanation paths to prevent memory explosion
            int maxPaths = Math.min(axSets.size(), MAX_EXPLANATION_PATHS);
            for (int i = 0; i < maxPaths; i++) {
                List<String> set = axSets.get(i);
                ArrayNode arr = jsonMapper.createArrayNode();
                // OPTIMIZED: Limit axioms per path
                int maxAxioms = Math.min(set.size(), MAX_AXIOMS_PER_PATH);
                for (int j = 0; j < maxAxioms; j++) {
                    arr.add(set.get(j));
                }
                if (arr.size() > 0) {
                    // Add tag to the explanation array (don't count in size)
                    String tag = generateTagForExplanation(set);
                    arr.add("TAG:" + tag);
                    explanationsArray.add(arr);
                }
            }
        }

        ObjectNode sizeNode = jsonMapper.createObjectNode();
        if (explanationsArray.size() > 0) {
            int min = Integer.MAX_VALUE, max = 0;
            for (int i = 0; i < explanationsArray.size(); i++) {
                // Count axioms excluding the TAG element
                int c = 0;
                for (int j = 0; j < explanationsArray.get(i).size(); j++) {
                    String element = explanationsArray.get(i).get(j).asText();
                    if (!element.startsWith("TAG:")) {
                        c++;
                    }
                }
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
    
    /**
     * Generate a tag for an explanation based on its axioms
     */
    private String generateTagForExplanation(List<String> axioms) {
        List<String> tags = new ArrayList<>();
        
        for (String axiom : axioms) {
            // Check for transitivity
            if (axiom.contains("TransitiveObjectProperty") || axiom.contains("transitive")) {
                tags.add("T");
            }
            
            // Check for role hierarchies
            if (axiom.contains("SubPropertyOf") || axiom.contains("SubClassOf")) {
                tags.add("H");
            }
            
            // Check for inverse roles - improved detection
            if (axiom.contains("InverseObjectProperty") || axiom.contains("InverseOf") || 
                axiom.contains("inverse") || axiom.contains("Inverse")) {
                tags.add("I");
            }
            
            // Check for cardinality restrictions
            if (axiom.contains("MinCardinality") || axiom.contains("MaxCardinality") || 
                axiom.contains("ExactCardinality") || axiom.contains("cardinality")) {
                tags.add("R");
            }
            
            // Check for complex roles (role composition/property chain only)
            if (axiom.contains("SubPropertyChain") || axiom.contains("propertyChainAxiom") || (axiom.contains("o") && axiom.contains("SubPropertyOf"))) {
                tags.add("C");
            }
            
            // Check for nominals
            if (axiom.contains("ObjectOneOf") || axiom.contains("DataOneOf") || axiom.contains("nominal")) {
                tags.add("N");
            }
            
            // Check for symmetry
            if (axiom.contains("SymmetricObjectProperty") || axiom.contains("symmetric")) {
                tags.add("S");
            }
        }
        // Sort tags alphabetically, but preserve duplicates
        tags.sort(String::compareTo);
        return String.join("", tags);
    }

    private List<List<String>> parseExplanationAxioms(String explanation,
                                                      String subject,
                                                      String object,
                                                      String predicate) {
        List<List<String>> results = new ArrayList<>();
        if (explanation == null || explanation.trim().isEmpty()) {
            return results;
        }
        if (explanation.contains("Directly asserted")) {
            List<String> directAsserted = new ArrayList<>();
            directAsserted.add("Directly asserted");
            results.add(directAsserted);
            return results;
        }

        List<String> currentPath = null;
        for (String line : explanation.split("\n")) {
            line = line.trim();
            if (line.startsWith("Path ")) {
                if (currentPath != null && !currentPath.isEmpty()) {
                    results.add(currentPath);
                }
                currentPath = new ArrayList<>();
            } else if (line.startsWith("-")) {
                if (currentPath == null) {
                    // This handles cases where explanation does not start with a "Path" header
                    currentPath = new ArrayList<>();
                }
                String axiom = line.substring(1).trim();
                if (axiom.contains(" AND ")) {
                    String[] parts = axiom.split(" AND ");
                    for (String part : parts) {
                        currentPath.add(part.trim());
                    }
                } else {
                    currentPath.add(axiom);
                }
            }
        }
        if (currentPath != null && !currentPath.isEmpty()) {
            results.add(currentPath);
        }

        // If no paths were found but the explanation is not empty, treat the whole thing as one path
        if (results.isEmpty() && !explanation.trim().isEmpty() && !explanation.contains("Directly asserted")) {
            currentPath = new ArrayList<>();
            for (String line : explanation.split("\n")) {
                line = line.trim();
                if (!line.isEmpty()) {
                    currentPath.add(line);
                }
            }
            if (!currentPath.isEmpty()) {
                results.add(currentPath);
            }
        }
        return results;
    }

    /**
     * OPTIMIZED: Simplified explanation stats calculation
     */
    private ExplanationStats calculateExplanationStats(String sparql, String tripleKey) {
        // First try the direct sparql key
        ObjectNode explanationNode = explanationsMap.get(sparql);

        // If not found, try the triple key in tripleExplanationsMap
        if (explanationNode == null) {
            explanationNode = tripleExplanationsMap.get(tripleKey);
        }

        // If still not found, try alternative formats
        if (explanationNode == null) {
            String cleanSparql = sparql.contains("|") ? sparql.substring(0, sparql.indexOf("|")) : sparql;
            explanationNode = explanationsMap.get(cleanSparql);
        }

        if (explanationNode != null) {
            double minSize = 0.0;
            double maxSize = 0.0;
            double count = 0.0;

            // Extract size information
            if (explanationNode.has("size")) {
                JsonNode sizeNode = explanationNode.get("size");
                if (sizeNode.has("min") && sizeNode.has("max")) {
                    minSize = sizeNode.get("min").asDouble();
                    maxSize = sizeNode.get("max").asDouble();
                }
            }

            // Extract explanation count
            if (explanationNode.has("explanationCount")) {
                count = explanationNode.get("explanationCount").asDouble();
            }

            return new ExplanationStats(minSize, maxSize, count);
        }

        // Log when we can't find explanations for debugging
        LOGGER.warn("No explanation found for sparql: {} or triple: {}", sparql, tripleKey);
        return new ExplanationStats(0.0, 0.0, 0.0);
    }

    /**
     * Helper class to hold explanation statistics
     */
    private static class ExplanationStats {
        final double avgMin;
        final double avgMax;
        final double avgCount;

        ExplanationStats(double avgMin, double avgMax, double avgCount) {
            this.avgMin = avgMin;
            this.avgMax = avgMax;
            this.avgCount = avgCount;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;

        logMemoryStats();
        LOGGER.info("Starting close process with {} explanations, {} triples",
                explanationsMap.size(), tripleExplanationsMap.size());

        if (csvPrinter != null) {
            csvPrinter.close();
            LOGGER.info("CSV file closed: {}", csvFilePath);
        }

        // Clear some maps to free memory before processing
        LOGGER.info("Freeing memory before JSON processing...");
        System.gc(); // Suggest garbage collection

        // Update explanations with cross-referenced task IDs
        updateExplanationsWithCrossReferences();

        // Build JSON from current session with memory optimization
        ObjectNode newRootNode = jsonMapper.createObjectNode();
        int processedTriples = 0;

        for (Map.Entry<String, Set<String>> entry : tripleToSparqlQueries.entrySet()) {
            String tripleKey = entry.getKey();
            String root = tripleKeyToRoot.getOrDefault(tripleKey, "UNKNOWN");

            ObjectNode fileNode = newRootNode.has(root)
                    ? (ObjectNode) newRootNode.get(root)
                    : jsonMapper.createObjectNode();
            newRootNode.set(root, fileNode);

            // Collect and limit SPARQL queries
            Set<String> allQueries = entry.getValue();
            if (allQueries.size() > MAX_SPARQL_QUERIES_PER_TRIPLE) {
                allQueries = allQueries.stream()
                        .limit(MAX_SPARQL_QUERIES_PER_TRIPLE)
                        .collect(Collectors.toSet());
                LOGGER.warn("Limited SPARQL queries for triple {} to {} entries", tripleKey, MAX_SPARQL_QUERIES_PER_TRIPLE);
            }

            ObjectNode explanationNode = tripleExplanationsMap.get(tripleKey);

            if (explanationNode != null) {
                // Add all related SPARQL queries as an array
                ArrayNode sparqlQueriesArray = jsonMapper.createArrayNode();
                for (String query : allQueries) {
                    // Clean up query if it has the "|answer" suffix from grouped queries
                    String cleanQuery = query.contains("|") ? query.substring(0, query.indexOf("|")) : query;
                    sparqlQueriesArray.add(cleanQuery);
                }
                explanationNode.set("sparqlQueries", sparqlQueriesArray);
            }

            // Use tripleKey (Ada|sibling|Bob) as the JSON key
            fileNode.set(tripleKey, explanationNode);

            processedTriples++;
            if (processedTriples % 50 == 0) {
                LOGGER.info("Processed {} triples for JSON", processedTriples);
                System.gc(); // Periodic garbage collection
            }
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

        // Merge existing and new data - with enhanced task ID and SPARQL query merging
        LOGGER.info("Starting merge of JSON data");
        Iterator<Map.Entry<String, JsonNode>> fields = newRootNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String rootEntity = entry.getKey();
            ObjectNode rootContent = (ObjectNode) entry.getValue();

            LOGGER.info("Processing root entity: {}", rootEntity);

            if (existingRootNode.has(rootEntity)) {
                LOGGER.info("Found existing data for entity {}", rootEntity);
                final ObjectNode existingContent = (ObjectNode) existingRootNode.get(rootEntity);
                Iterator<Map.Entry<String, JsonNode>> contentFields = rootContent.fields();

                while (contentFields.hasNext()) {
                    Map.Entry<String, JsonNode> field = contentFields.next();
                    String tripleKey = field.getKey(); // Now this is Ada|sibling|Bob format
                    ObjectNode newExplanation = (ObjectNode) field.getValue();

                    // Merge both task IDs and SPARQL queries if explanation already exists
                    if (existingContent.has(tripleKey)) {
                        ObjectNode existingExplanation = (ObjectNode) existingContent.get(tripleKey);

                        LOGGER.info("  Merging data for existing triple: {}", tripleKey);

                        // Merge task IDs
                        Set<String> mergedTaskIds = new HashSet<>();
                        if (existingExplanation.has("taskIds")) {
                            JsonNode existingTaskIds = existingExplanation.get("taskIds");
                            if (existingTaskIds.isArray()) {
                                for (JsonNode taskId : existingTaskIds) {
                                    mergedTaskIds.add(taskId.asText());
                                }
                            }
                        }
                        if (newExplanation.has("taskIds")) {
                            JsonNode newTaskIds = newExplanation.get("taskIds");
                            if (newTaskIds.isArray()) {
                                for (JsonNode taskId : newTaskIds) {
                                    mergedTaskIds.add(taskId.asText());
                                }
                            }
                        }

                        // Merge SPARQL queries
                        Set<String> mergedQueries = new HashSet<>();
                        if (existingExplanation.has("sparqlQueries")) {
                            JsonNode existingQueries = existingExplanation.get("sparqlQueries");
                            if (existingQueries.isArray()) {
                                for (JsonNode query : existingQueries) {
                                    mergedQueries.add(query.asText());
                                }
                            }
                        }
                        if (newExplanation.has("sparqlQueries")) {
                            JsonNode newQueries = newExplanation.get("sparqlQueries");
                            if (newQueries.isArray()) {
                                for (JsonNode query : newQueries) {
                                    mergedQueries.add(query.asText());
                                }
                            }
                        }

                        // Create merged arrays
                        ArrayNode mergedTaskIdsArray = jsonMapper.createArrayNode();
                        mergedTaskIds.stream()
                                .sorted()
                                .forEach(mergedTaskIdsArray::add);

                        ArrayNode mergedQueriesArray = jsonMapper.createArrayNode();
                        mergedQueries.stream()
                                .sorted()
                                .forEach(mergedQueriesArray::add);

                        // Update the new explanation with merged data
                        newExplanation.set("taskIds", mergedTaskIdsArray);
                        newExplanation.set("sparqlQueries", mergedQueriesArray);

                        LOGGER.info("    Merged {} task IDs and {} SPARQL queries for triple {}",
                                mergedTaskIds.size(), mergedQueries.size(), tripleKey);
                    } else {
                        LOGGER.info("  Adding new triple: {}", tripleKey);
                    }

                    // Set the updated explanation
                    existingContent.set(tripleKey, newExplanation);
                }
            } else {
                LOGGER.info("Adding new entity: {}", rootEntity);
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

        // Clear maps to free memory
        explanationsMap.clear();
        tripleExplanationsMap.clear();
        tripleToSparqlQueries.clear();
        tripleToTaskIds.clear();

        closed = true;
    }
}