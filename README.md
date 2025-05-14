# OWL Inference Explainer

A tool for generating explanations of inferences made by the Pellet OWL reasoner, helping users understand why specific relations hold in their ontologies.

## Overview

OWL Inference Explainer is a Java application that analyzes OWL ontologies, identifies inferences made by the Pellet reasoner, and generates detailed explanations for these inferences. The tool provides insights into three types of relations:

1. **Property Assertions**: Explains why individual A has property P relating to individual B
2. **Class Membership**: Explains why individual A is an instance of class C
3. **Class Subsumption**: Explains why class A is a subclass of class B

For each inference, the tool provides both a binary answer (true/false) and detailed explanations showing the logical path from asserted facts to inferred conclusions.

## Features

- Generates SPARQL queries for all inferences in the ontology
- Explains direct and indirect property relationships
- Identifies class membership through various inference paths
- Explains class hierarchy relationships
- Handles inverse properties, subproperties, and equivalent classes
- Outputs both CSV summaries and detailed JSON explanations
- Supports multi-choice explanations with grouped answers

## Output Format

The tool produces two output files:

1. **CSV Summary** (`SPARQL_questions.csv`): Contains all SPARQL queries with answer types, predicates, and answers
2. **JSON Explanations** (`explanations.json`): Contains detailed explanations for each inference, including:
   - The inferred relationship (subject, predicate, object)
   - One or more explanation paths showing the reasoning steps
   - Size metrics indicating explanation complexity

## Getting Started

### Prerequisites

- Java 11 or higher
- Maven 3.6 or higher

### Installation

1. Clone the repository:
git clone https://github.com/SaraFlht/OwlInferenceExplainer.java
cd owl-inference-explainer

2. Build the project with Maven:
mvn clean install

### Usage

1. Run the application with a path to your OWL ontology file:
java -jar target/owl-inference-explainer.jar /path/to/your/ontology.owl

2. Or use the default sample ontology included in the project:
java -jar target/owl-inference-explainer.jar

3. The output files will be generated in the current directory:
- `SPARQL_questions.csv`
- `explanations.json`
