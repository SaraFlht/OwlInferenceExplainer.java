package com.example.explanation;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Service to tag explanations based on their complexity features
 */
public class ExplanationTagger {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ExplanationTagger.class);
    
    // Tag definitions
    public static final String TAG_TRANSITIVITY = "T";      // Role transitivity
    public static final String TAG_HIERARCHY = "H";         // Role hierarchies
    public static final String TAG_INVERSE = "I";           // Inverse roles
    public static final String TAG_CARDINALITY = "R";       // Cardinality restrictions
    public static final String TAG_COMPLEX = "C";           // Complex roles
    public static final String TAG_NOMINALS = "N";          // Nominals
    public static final String TAG_SYMMETRY = "S";          // Symmetry
    
    /**
     * Tag a single explanation path
     */
    public String tagExplanation(ExplanationPath path) {
        Set<String> tags = new HashSet<>();
        
        for (OWLAxiom axiom : path.getAxioms()) {
            // Check for transitivity
            if (isTransitiveAxiom(axiom)) {
                tags.add(TAG_TRANSITIVITY);
            }
            
            // Check for role hierarchies
            if (isRoleHierarchyAxiom(axiom)) {
                tags.add(TAG_HIERARCHY);
            }
            
            // Check for inverse roles
            if (isInverseRoleAxiom(axiom)) {
                tags.add(TAG_INVERSE);
            }
            
            // Check for cardinality restrictions
            if (isCardinalityRestrictionAxiom(axiom)) {
                tags.add(TAG_CARDINALITY);
            }
            
            // Check for complex roles
            if (isComplexRoleAxiom(axiom)) {
                tags.add(TAG_COMPLEX);
            }
            
            // Check for nominals
            if (isNominalAxiom(axiom)) {
                tags.add(TAG_NOMINALS);
            }
            
            // Check for symmetry
            if (isSymmetricAxiom(axiom)) {
                tags.add(TAG_SYMMETRY);
            }
        }
        
        // Sort tags alphabetically for consistent ordering
        List<String> sortedTags = new ArrayList<>(tags);
        Collections.sort(sortedTags);
        
        return String.join("", sortedTags);
    }
    
    /**
     * Tag a list of explanation paths
     */
    public List<String> tagExplanations(List<ExplanationPath> paths) {
        List<String> tags = new ArrayList<>();
        
        for (ExplanationPath path : paths) {
            String tag = tagExplanation(path);
            tags.add(tag);
            LOGGER.debug("Tagged explanation: {} -> {}", path.getDescription(), tag);
        }
        
        return tags;
    }
    
    /**
     * Check if an axiom involves transitivity
     */
    private boolean isTransitiveAxiom(OWLAxiom axiom) {
        if (axiom instanceof OWLTransitiveObjectPropertyAxiom) {
            return true;
        }
        
        // Check for transitive properties in property chains
        if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            OWLSubPropertyChainOfAxiom chainAx = (OWLSubPropertyChainOfAxiom) axiom;
            // If chain has more than 2 properties, it might involve transitivity
            if (chainAx.getPropertyChain().size() > 2) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if an axiom involves role hierarchies
     */
    private boolean isRoleHierarchyAxiom(OWLAxiom axiom) {
        return axiom instanceof OWLSubObjectPropertyOfAxiom ||
               axiom instanceof OWLSubDataPropertyOfAxiom ||
               axiom instanceof OWLSubAnnotationPropertyOfAxiom;
    }
    
    /**
     * Check if an axiom involves inverse roles
     */
    private boolean isInverseRoleAxiom(OWLAxiom axiom) {
        return axiom instanceof OWLInverseObjectPropertiesAxiom;
    }
    
    /**
     * Check if an axiom involves cardinality restrictions
     */
    private boolean isCardinalityRestrictionAxiom(OWLAxiom axiom) {
        if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom classAx = (OWLClassAssertionAxiom) axiom;
            OWLClassExpression classExpr = classAx.getClassExpression();
            
            // Check for cardinality restrictions in class expressions
            return containsCardinalityRestriction(classExpr);
        }
        
        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) axiom;
            
            // Check both subclass and superclass for cardinality restrictions
            return containsCardinalityRestriction(subClassAx.getSubClass()) ||
                   containsCardinalityRestriction(subClassAx.getSuperClass());
        }
        
        return false;
    }
    
    /**
     * Check if a class expression contains cardinality restrictions
     */
    private boolean containsCardinalityRestriction(OWLClassExpression classExpr) {
        if (classExpr == null) return false;
        
        // Check for various cardinality restrictions
        return classExpr.getClassExpressionType() == ClassExpressionType.OBJECT_MIN_CARDINALITY ||
               classExpr.getClassExpressionType() == ClassExpressionType.OBJECT_MAX_CARDINALITY ||
               classExpr.getClassExpressionType() == ClassExpressionType.OBJECT_EXACT_CARDINALITY ||
               classExpr.getClassExpressionType() == ClassExpressionType.DATA_MIN_CARDINALITY ||
               classExpr.getClassExpressionType() == ClassExpressionType.DATA_MAX_CARDINALITY ||
               classExpr.getClassExpressionType() == ClassExpressionType.DATA_EXACT_CARDINALITY;
    }
    
    /**
     * Check if an axiom involves complex roles
     */
    private boolean isComplexRoleAxiom(OWLAxiom axiom) {
        // Property chains are complex roles
        if (axiom instanceof OWLSubPropertyChainOfAxiom) {
            OWLSubPropertyChainOfAxiom chainAx = (OWLSubPropertyChainOfAxiom) axiom;
            return chainAx.getPropertyChain().size() > 1;
        }
        
        // Functional, inverse functional, reflexive, irreflexive properties
        if (axiom instanceof OWLFunctionalObjectPropertyAxiom ||
            axiom instanceof OWLInverseFunctionalObjectPropertyAxiom ||
            axiom instanceof OWLReflexiveObjectPropertyAxiom ||
            axiom instanceof OWLIrreflexiveObjectPropertyAxiom) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if an axiom involves nominals
     */
    private boolean isNominalAxiom(OWLAxiom axiom) {
        if (axiom instanceof OWLClassAssertionAxiom) {
            OWLClassAssertionAxiom classAx = (OWLClassAssertionAxiom) axiom;
            OWLClassExpression classExpr = classAx.getClassExpression();
            
            // Check for object one of (nominals)
            return classExpr.getClassExpressionType() == ClassExpressionType.OBJECT_ONE_OF;
        }
        
        if (axiom instanceof OWLSubClassOfAxiom) {
            OWLSubClassOfAxiom subClassAx = (OWLSubClassOfAxiom) axiom;
            
            // Check both subclass and superclass for nominals
            return containsNominal(subClassAx.getSubClass()) ||
                   containsNominal(subClassAx.getSuperClass());
        }
        
        return false;
    }
    
    /**
     * Check if a class expression contains nominals
     */
    private boolean containsNominal(OWLClassExpression classExpr) {
        if (classExpr == null) return false;
        
        return classExpr.getClassExpressionType() == ClassExpressionType.OBJECT_ONE_OF;
    }
    
    /**
     * Check if an axiom involves symmetry
     */
    private boolean isSymmetricAxiom(OWLAxiom axiom) {
        return axiom instanceof OWLSymmetricObjectPropertyAxiom ||
               axiom instanceof OWLAsymmetricObjectPropertyAxiom;
    }
    
    /**
     * Get a description of what each tag means
     */
    public static Map<String, String> getTagDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put(TAG_TRANSITIVITY, "Role transitivity");
        descriptions.put(TAG_HIERARCHY, "Role hierarchies");
        descriptions.put(TAG_INVERSE, "Inverse roles");
        descriptions.put(TAG_CARDINALITY, "Cardinality restrictions");
        descriptions.put(TAG_COMPLEX, "Complex roles");
        descriptions.put(TAG_NOMINALS, "Nominals");
        descriptions.put(TAG_SYMMETRY, "Symmetry");
        return descriptions;
    }
    
    /**
     * Get a detailed analysis of an explanation path
     */
    public Map<String, Object> analyzeExplanation(ExplanationPath path) {
        Map<String, Object> analysis = new HashMap<>();
        
        analysis.put("tag", tagExplanation(path));
        analysis.put("type", path.getType().getDisplayName());
        analysis.put("complexity", path.getComplexity());
        analysis.put("description", path.getDescription());
        
        // Count different types of axioms
        Map<String, Integer> axiomCounts = new HashMap<>();
        for (OWLAxiom axiom : path.getAxioms()) {
            String axiomType = axiom.getAxiomType().getName();
            axiomCounts.put(axiomType, axiomCounts.getOrDefault(axiomType, 0) + 1);
        }
        analysis.put("axiomCounts", axiomCounts);
        
        // Check for specific features
        Map<String, Boolean> features = new HashMap<>();
        features.put("transitivity", hasTransitivity(path));
        features.put("hierarchy", hasHierarchy(path));
        features.put("inverse", hasInverse(path));
        features.put("cardinality", hasCardinality(path));
        features.put("complex", hasComplex(path));
        features.put("nominals", hasNominals(path));
        features.put("symmetry", hasSymmetry(path));
        analysis.put("features", features);
        
        return analysis;
    }
    
    private boolean hasTransitivity(ExplanationPath path) {
        return path.getAxioms().stream().anyMatch(this::isTransitiveAxiom);
    }
    
    private boolean hasHierarchy(ExplanationPath path) {
        return path.getAxioms().stream().anyMatch(this::isRoleHierarchyAxiom);
    }
    
    private boolean hasInverse(ExplanationPath path) {
        return path.getAxioms().stream().anyMatch(this::isInverseRoleAxiom);
    }
    
    private boolean hasCardinality(ExplanationPath path) {
        return path.getAxioms().stream().anyMatch(this::isCardinalityRestrictionAxiom);
    }
    
    private boolean hasComplex(ExplanationPath path) {
        return path.getAxioms().stream().anyMatch(this::isComplexRoleAxiom);
    }
    
    private boolean hasNominals(ExplanationPath path) {
        return path.getAxioms().stream().anyMatch(this::isNominalAxiom);
    }
    
    private boolean hasSymmetry(ExplanationPath path) {
        return path.getAxioms().stream().anyMatch(this::isSymmetricAxiom);
    }
} 