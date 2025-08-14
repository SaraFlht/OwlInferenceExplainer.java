# Import necessary libraries
import rdflib
from rdflib import Graph, URIRef, Literal, BNode
from rdflib.namespace import RDF, RDFS, OWL
from pathlib import Path
import os
import re
import json
import inflect
from pathlib import Path

# SimpleNLG imports
try:
    from simplenlg.framework import NLGFactory, CoordinatedPhraseElement
    from simplenlg.lexicon import Lexicon
    from simplenlg.realiser.english import Realiser
    from simplenlg.phrasespec import SPhraseSpec
    from simplenlg.features import Feature, Tense, NumberAgreement
    SIMPLENLG_AVAILABLE = True
    print("✅ SimpleNLG available")
except ImportError:
    print("⚠️ SimpleNLG not available. Install with: pip install simplenlg-en")
    SIMPLENLG_AVAILABLE = False

# Navigate to project root
try:
    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parent.parent
    os.chdir(project_root)
except NameError:
    print("Could not dynamically set project root. Assuming current working directory is correct.")
    pass

print(f"Working directory set to: {os.getcwd()}")

# Initialize SimpleNLG
if SIMPLENLG_AVAILABLE:
    lexicon = Lexicon.getDefaultLexicon()
    nlg_factory = NLGFactory(lexicon)
    realiser = Realiser(lexicon)
    print("✅ SimpleNLG initialized")

p = inflect.engine()

def clean_entity_name(name):
    """Clean entity names to be more human-readable for any domain"""
    if not name:
        return ""

    # Remove common technical suffixes
    name = re.sub(r'_\d{4}$', '', name)  # Remove years like _1885
    name = re.sub(r'_\d+$', '', name)    # Remove any trailing numbers
    name = re.sub(r'_v\d+$', '', name)   # Remove version numbers like _v1
    name = re.sub(r'_\w{2,3}$', '', name) # Remove short suffixes like _en, _us

    # Convert camelCase to Title Case
    name = re.sub(r'([a-z])([A-Z])', r'\1 \2', name)

    # Replace underscores and hyphens with spaces
    name = name.replace('_', ' ').replace('-', ' ')

    # Remove common prefixes
    prefixes_to_remove = ['owl:', 'rdf:', 'rdfs:', 'xsd:', 'foaf:', 'dc:', 'dct:']
    for prefix in prefixes_to_remove:
        if name.lower().startswith(prefix):
            name = name[len(prefix):]

    # Capitalize properly and clean up spaces
    words = [word.capitalize() for word in name.split() if word]
    return ' '.join(words)

def get_nice_label(g, entity):
    """Get a nice label for an entity from any domain"""
    # Priority order for labels
    label_properties = [
        RDFS.label,
        URIRef("http://www.w3.org/2004/02/skos/core#prefLabel"),
        URIRef("http://purl.org/dc/elements/1.1/title"),
        URIRef("http://xmlns.com/foaf/0.1/name"),
        URIRef("http://schema.org/name")
    ]

    # Try to find a label using common label properties
    for label_prop in label_properties:
        label = g.value(entity, label_prop)
        if label:
            return clean_entity_name(str(label))

    # If no label found, extract from URI
    if isinstance(entity, URIRef):
        uri_str = str(entity)
        if '#' in uri_str:
            name = uri_str.split('#')[-1]
        elif '/' in uri_str:
            name = uri_str.split('/')[-1]
        else:
            name = uri_str
        return clean_entity_name(name)

    return None

def create_simple_sentence(subject, verb, object_val, is_plural_subject=False):
    """Create a grammatically correct sentence using SimpleNLG"""
    if not SIMPLENLG_AVAILABLE:
        return f"{subject} {verb} {object_val}."

    try:
        # Create sentence
        sentence = nlg_factory.createClause()
        sentence.setSubject(subject)
        sentence.setVerb(verb)
        if object_val:
            sentence.setObject(object_val)

        # Set number agreement
        if is_plural_subject:
            sentence.setFeature(Feature.NUMBER, NumberAgreement.PLURAL)

        # Realize the sentence
        output = realiser.realiseSentence(sentence)
        return output.strip()

    except Exception as e:
        # Fallback to simple concatenation
        return f"{subject} {verb} {object_val}."

def create_list_sentence(subject, verb, object_list):
    """Create a sentence with a coordinated list of objects"""
    if not SIMPLENLG_AVAILABLE or not object_list:
        if len(object_list) == 1:
            return f"{subject} {verb} {object_list[0]}."
        elif len(object_list) == 2:
            return f"{subject} {verb} {object_list[0]} and {object_list[1]}."
        else:
            return f"{subject} {verb} {', '.join(object_list[:-1])}, and {object_list[-1]}."

    try:
        # Create coordinated phrase for multiple objects
        if len(object_list) > 1:
            coord_phrase = nlg_factory.createCoordinatedPhrase()
            for obj in object_list:
                coord_phrase.addCoordinate(obj)

            sentence = nlg_factory.createClause()
            sentence.setSubject(subject)
            sentence.setVerb(verb)
            sentence.setObject(coord_phrase)
        else:
            sentence = nlg_factory.createClause()
            sentence.setSubject(subject)
            sentence.setVerb(verb)
            sentence.setObject(object_list[0])

        output = realiser.realiseSentence(sentence)
        return output.strip()

    except Exception as e:
        # Fallback
        if len(object_list) == 1:
            return f"{subject} {verb} {object_list[0]}."
        return f"{subject} {verb} {', '.join(object_list[:-1])} and {object_list[-1]}."

def get_property_verb(property_name):
    """Convert property names to natural verbs for any domain"""
    prop_lower = property_name.lower()

    # Common property patterns and their natural language equivalents
    verb_mappings = {
        'has': 'has',
        'is': 'is',
        'contains': 'contains',
        'includes': 'includes',
        'belongs': 'belongs to',
        'relates': 'relates to',
        'connects': 'connects to',
        'links': 'links to',
        'associated': 'is associated with',
        'part': 'is part of',
        'member': 'is a member of',
        'type': 'is of type',
        'kind': 'is a kind of',
        'instance': 'is an instance of'
    }

    # Check for common patterns
    for pattern, verb in verb_mappings.items():
        if pattern in prop_lower:
            return verb

    # Default: use the property name as a verb
    return prop_lower

def describe_class_with_nlg(g, cls, classes, subclass_relations, equivalent_class_relations, obj_properties, property_domains, property_ranges):
    """Generate natural language description of a class for any domain"""
    descriptions = []
    cls_name = get_nice_label(g, cls)

    if not cls_name:
        return ""

    # Subclass relationships
    parents = [o for s, o in subclass_relations if s == cls]
    if parents:
        parent_names = [get_nice_label(g, parent) for parent in parents if get_nice_label(g, parent)]
        if parent_names:
            if len(parent_names) == 1:
                desc = create_simple_sentence(cls_name, "is a type of", parent_names[0])
            else:
                desc = create_list_sentence(cls_name, "is a type of", parent_names)
            descriptions.append(desc)

    # Equivalent classes
    equivalents = [o for s, o in equivalent_class_relations if s == cls]
    if equivalents:
        equiv_names = []
        for eq in equivalents:
            if isinstance(eq, URIRef):
                eq_name = get_nice_label(g, eq)
                if eq_name:
                    equiv_names.append(eq_name)

        if equiv_names:
            if len(equiv_names) == 1:
                desc = create_simple_sentence(cls_name, "is equivalent to", equiv_names[0])
            else:
                desc = create_list_sentence(cls_name, "is equivalent to", equiv_names)
            descriptions.append(desc)

    # Properties that have this class as domain
    domain_props = [prop for prop in obj_properties if cls in property_domains.get(prop, set())]
    if domain_props:
        capabilities = []
        for prop in domain_props:
            prop_name = get_nice_label(g, prop)
            if prop_name:
                verb = get_property_verb(prop_name)
                ranges = property_ranges.get(prop, set())
                range_names = [get_nice_label(g, r) for r in ranges if get_nice_label(g, r)]

                if range_names:
                    if len(range_names) == 1:
                        capabilities.append(f"{verb} {range_names[0]}")
                    else:
                        capabilities.append(f"{verb} {', '.join(range_names[:-1])} or {range_names[-1]}")
                else:
                    capabilities.append(f"{verb} other entities")

        if capabilities:
            capability_text = "Instances of this class can " + ", ".join(capabilities)
            descriptions.append(capability_text + ".")

    # Properties that have this class as range
    range_props = [prop for prop in obj_properties if cls in property_ranges.get(prop, set())]
    if range_props:
        domain_entities = set()
        for prop in range_props:
            domains = property_domains.get(prop, set())
            for domain in domains:
                domain_name = get_nice_label(g, domain)
                if domain_name:
                    domain_entities.add(domain_name)

        if domain_entities:
            domain_list = list(domain_entities)
            if len(domain_list) == 1:
                desc = create_simple_sentence(
                    f"Instances of {cls_name}",
                    "can be related to",
                    domain_list[0]
                )
            else:
                desc = create_list_sentence(
                    f"Instances of {cls_name}",
                    "can be related to",
                    domain_list
                )
            descriptions.append(desc)

    # Final description if no specific relationships found
    if not descriptions:
        desc = create_simple_sentence(cls_name, "is", "a class in this ontology")
        descriptions.append(desc)

    return " ".join(descriptions)

def describe_property_with_nlg(g, prop, obj_properties, property_domains, property_ranges):
    """Generate natural language description of a property for any domain"""
    descriptions = []
    prop_name = get_nice_label(g, prop)

    if not prop_name:
        return ""

    # Check property characteristics
    characteristics = []
    for s, p, o in g.triples((prop, RDF.type, None)):
        if o == OWL.SymmetricProperty:
            characteristics.append("symmetric")
        elif o == OWL.TransitiveProperty:
            characteristics.append("transitive")
        elif o == OWL.FunctionalProperty:
            characteristics.append("functional")
        elif o == OWL.InverseFunctionalProperty:
            characteristics.append("inverse functional")

    # Basic description
    desc = create_simple_sentence(prop_name, "is", "a property that relates entities in this ontology")
    descriptions.append(desc)

    # Add characteristics
    if "symmetric" in characteristics:
        desc = create_simple_sentence(
            "This property",
            "is",
            "symmetric, meaning if A relates to B, then B relates to A in the same way"
        )
        descriptions.append(desc)

    if "transitive" in characteristics:
        desc = create_simple_sentence(
            "This property",
            "is",
            "transitive, meaning it can form chains of relationships"
        )
        descriptions.append(desc)

    if "functional" in characteristics:
        desc = create_simple_sentence(
            "This property",
            "is",
            "functional, meaning each entity can have at most one value for this property"
        )
        descriptions.append(desc)

    # Domain and range information
    domains = property_domains.get(prop, set())
    ranges = property_ranges.get(prop, set())

    domain_names = [get_nice_label(g, d) for d in domains if get_nice_label(g, d)]
    range_names = [get_nice_label(g, r) for r in ranges if get_nice_label(g, r)]

    if domain_names:
        if len(domain_names) == 1:
            desc = create_simple_sentence(
                "The domain of this property",
                "is",
                f"{domain_names[0]}, meaning only {domain_names[0]} instances can have this property"
            )
        else:
            domain_text = ", ".join(domain_names[:-1]) + f" and {domain_names[-1]}"
            desc = create_simple_sentence(
                "The domain of this property",
                "includes",
                f"{domain_text}"
            )
        descriptions.append(desc)

    if range_names:
        if len(range_names) == 1:
            desc = create_simple_sentence(
                "The range of this property",
                "is",
                f"{range_names[0]}, meaning this property can only point to {range_names[0]} instances"
            )
        else:
            range_text = ", ".join(range_names[:-1]) + f" and {range_names[-1]}"
            desc = create_simple_sentence(
                "The range of this property",
                "includes",
                f"{range_text}"
            )
        descriptions.append(desc)

    return " ".join(descriptions)

def describe_individual_with_nlg(g, ind, classes, obj_properties):
    """Generate natural language description of an individual for any domain"""
    descriptions = []
    ind_name = get_nice_label(g, ind)

    if not ind_name:
        return ""

    # Get types/classes
    types = []
    for s, p, o in g.triples((ind, RDF.type, None)):
        if o != OWL.NamedIndividual and o in classes:
            types.append(o)

    if types:
        type_names = [get_nice_label(g, t) for t in types if get_nice_label(g, t)]
        type_names = [name for name in type_names if name and isinstance(name, str)]
        if type_names:
            if len(type_names) == 1:
                desc = create_simple_sentence(ind_name, "is", f"an instance of {type_names[0]}")
            else:
                type_text = ", ".join([f"an instance of {t}" for t in type_names])
                desc = f"{ind_name} is {type_text}."
            descriptions.append(desc)

    # Get outgoing relationships
    relationships = {}
    for s, p, o in g.triples((ind, None, None)):
        if p != RDF.type and isinstance(o, URIRef) and p in obj_properties:
            prop_name = get_nice_label(g, p)
            obj_name = get_nice_label(g, o)
            if prop_name and obj_name:
                if prop_name not in relationships:
                    relationships[prop_name] = []
                relationships[prop_name].append(obj_name)

    # Create sentences for relationships
    for prop_name, related_entities in relationships.items():
        verb = get_property_verb(prop_name)

        if len(related_entities) == 1:
            desc = create_simple_sentence(ind_name, verb, related_entities[0])
        else:
            desc = create_list_sentence(ind_name, verb, related_entities)
        descriptions.append(desc)

    # Get incoming relationships (others pointing to this individual)
    incoming_relationships = {}
    for s, p, o in g.triples((None, None, ind)):
        if isinstance(s, URIRef) and s != ind and p in obj_properties:
            subj_name = get_nice_label(g, s)
            prop_name = get_nice_label(g, p)
            if subj_name and prop_name:
                if prop_name not in incoming_relationships:
                    incoming_relationships[prop_name] = []
                incoming_relationships[prop_name].append(subj_name)

    # Create sentences for incoming relationships
    for prop_name, related_entities in incoming_relationships.items():
        verb = get_property_verb(prop_name)
        if len(related_entities) == 1:
            desc = create_simple_sentence(related_entities[0], verb, ind_name)
        else:
            subject = ", ".join(related_entities[:-1]) + f" and {related_entities[-1]}"
            desc = create_simple_sentence(subject, verb, ind_name, is_plural_subject=True)
        descriptions.append(desc)

    if not descriptions:
        desc = create_simple_sentence(ind_name, "is", "an individual in this ontology")
        descriptions.append(desc)

    return " ".join(descriptions)

def detect_domain_type(g, individuals, classes, obj_properties):
    """Detect the domain type of the ontology for better contextualization"""
    domain_indicators = {
        'family': ['person', 'man', 'woman', 'child', 'parent', 'marriage', 'sibling'],
        'organization': ['organization', 'company', 'employee', 'department', 'role'],
        'geography': ['country', 'city', 'region', 'location', 'place'],
        'biology': ['species', 'organism', 'gene', 'protein', 'cell'],
        'technology': ['software', 'hardware', 'system', 'component', 'device'],
        'academic': ['course', 'student', 'professor', 'university', 'research']
    }

    # Count domain-specific terms
    domain_scores = {domain: 0 for domain in domain_indicators}

    all_labels = []
    for entity_set in [individuals, classes, obj_properties]:
        for entity in entity_set:
            label = get_nice_label(g, entity)
            if label:
                all_labels.append(label.lower())

    text = ' '.join(all_labels)

    for domain, indicators in domain_indicators.items():
        for indicator in indicators:
            domain_scores[domain] += text.count(indicator)

    # Return the domain with highest score, or 'general' if no clear domain
    max_score = max(domain_scores.values())
    if max_score > 0:
        return max(domain_scores, key=domain_scores.get)
    return 'general'

def verbalize_ontology(ontology_file_path, output_dir):
    """Verbalize a single ontology file and save as JSON - works with any domain"""
    print(f"Processing: {ontology_file_path}")

    g = Graph()
    try:
        g.parse(ontology_file_path)
        print(f"Successfully loaded ontology with {len(g)} triples")
    except Exception as e:
        print(f"Error loading ontology: {e}")
        return None

    # Extract ontology structure
    classes = set()
    for s, p, o in g.triples((None, RDF.type, OWL.Class)):
        if isinstance(s, URIRef):
            classes.add(s)

    obj_properties = set()
    for s, p, o in g.triples((None, RDF.type, OWL.ObjectProperty)):
        if isinstance(s, URIRef):
            obj_properties.add(s)

    data_properties = set()
    for s, p, o in g.triples((None, RDF.type, OWL.DatatypeProperty)):
        if isinstance(s, URIRef):
            data_properties.add(s)

    # Get individuals
    individuals = set()
    for s, p, o in g.triples((None, RDF.type, OWL.NamedIndividual)):
        if isinstance(s, URIRef):
            individuals.add(s)

    # Fallback for individuals
    if not individuals:
        for s, p, o in g.triples((None, RDF.type, None)):
            if (o != OWL.Class and o != OWL.ObjectProperty and o != OWL.DatatypeProperty and
                    o != OWL.AnnotationProperty and o != OWL.Ontology and isinstance(s, URIRef)):
                individuals.add(s)

    # Detect domain type
    domain_type = detect_domain_type(g, individuals, classes, obj_properties)
    print(f"Detected domain type: {domain_type}")

    # Get relationships
    subclass_relations = []
    for s, p, o in g.triples((None, RDFS.subClassOf, None)):
        if s in classes and o in classes:
            subclass_relations.append((s, o))

    equivalent_class_relations = []
    for s, p, o in g.triples((None, OWL.equivalentClass, None)):
        if s in classes:
            equivalent_class_relations.append((s, o))

    # Get domain and range for properties
    property_domains = {}
    property_ranges = {}

    for prop in obj_properties:
        domains = set()
        for s, p, o in g.triples((prop, RDFS.domain, None)):
            domains.add(o)
        property_domains[prop] = domains

        ranges = set()
        for s, p, o in g.triples((prop, RDFS.range, None)):
            ranges.add(o)
        property_ranges[prop] = ranges

    # Create verbalization data with domain-aware description
    ontology_description = create_simple_sentence(
        f"This {domain_type} ontology",
        "contains information about",
        f"{len(individuals)} individuals, {len(classes)} classes, and {len(obj_properties)} properties"
    )

    verbalization_data = {
        "ontology": {
            "name": Path(ontology_file_path).stem,  # FIXED: Use Path().stem instead of os.path.basename().stem
            "domain": domain_type,
            "triples": len(g),
            "description": ontology_description
        },
        "classes": [],
        "objectProperties": [],
        "dataProperties": [],
        "individuals": []
    }

    # Add classes with enhanced descriptions
    print("Generating class descriptions...")
    for cls in sorted(classes, key=lambda x: get_nice_label(g, x) or ""):
        cls_name = get_nice_label(g, cls)
        if cls_name:
            cls_data = {
                "uri": str(cls),
                "classLabel": cls_name,
                "description": describe_class_with_nlg(g, cls, classes, subclass_relations, equivalent_class_relations, obj_properties, property_domains, property_ranges)
            }
            verbalization_data["classes"].append(cls_data)

    # Add object properties with enhanced descriptions
    print("Generating property descriptions...")
    for prop in sorted(obj_properties, key=lambda x: get_nice_label(g, x) or ""):
        prop_name = get_nice_label(g, prop)
        if prop_name:
            prop_data = {
                "uri": str(prop),
                "propertyLabel": prop_name,
                "description": describe_property_with_nlg(g, prop, obj_properties, property_domains, property_ranges)
            }
            verbalization_data["objectProperties"].append(prop_data)

    # Add data properties
    for prop in sorted(data_properties, key=lambda x: get_nice_label(g, x) or ""):
        prop_name = get_nice_label(g, prop)
        if prop_name:
            prop_data = {
                "uri": str(prop),
                "propertyLabel": prop_name,
                "description": create_simple_sentence(prop_name, "is", "a data property in this ontology")
            }
            verbalization_data["dataProperties"].append(prop_data)

    # Add individuals with enhanced descriptions
    print("Generating individual descriptions...")
    for ind in sorted(individuals, key=lambda x: get_nice_label(g, x) or ""):
        ind_name = get_nice_label(g, ind)
        if ind_name:
            ind_data = {
                "uri": str(ind),
                "individualLabel": ind_name,
                "description": describe_individual_with_nlg(g, ind, classes, obj_properties)
            }
            verbalization_data["individuals"].append(ind_data)

    # Save JSON file
    output_file = output_dir / f"{Path(ontology_file_path).stem}.json"  # FIXED: Use Path().stem
    with open(output_file, "w", encoding='utf-8') as json_file:
        json.dump(verbalization_data, json_file, indent=2, ensure_ascii=False)

    print(f"Saved verbalization to: {output_file}")
    return output_file

def main():
    """Main function to process ontology files - configurable for any domain"""
    import argparse

    parser = argparse.ArgumentParser(description='Verbalize ontology files to natural language JSON')
    parser.add_argument('--input-dir',
                        default='src/main/resources/FamilyOWL_1hop',
                        help='Input directory containing ontology files')
    parser.add_argument('--output-dir',
                        default='output/verbalized_ontologies/FamilyOWL_1hop',
                        help='Output directory for JSON files')
    parser.add_argument('--file-pattern',
                        default='*.ttl',
                        help='File pattern to match (e.g., *.ttl, *.owl, *.rdf)')

    args = parser.parse_args()

    # Create output directory
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # Define input directory
    input_dir = Path(args.input_dir)

    # Check if input directory exists
    if not input_dir.exists():
        print(f"Error: Input directory does not exist: {input_dir}")
        return

    # Find all matching files
    ontology_files = list(input_dir.glob(args.file_pattern))

    if not ontology_files:
        print(f"No files matching '{args.file_pattern}' found in {input_dir}")
        return

    print(f"Found {len(ontology_files)} ontology files to process:")
    for file in ontology_files:
        print(f"  - {file.name}")

    if SIMPLENLG_AVAILABLE:
        print("🎯 Using SimpleNLG for enhanced natural language generation")
    else:
        print("⚠️ SimpleNLG not available, using basic text generation")

    # Process each file
    successful = 0
    failed = 0

    for ontology_file in ontology_files:
        print(f"\n{'='*60}")
        try:
            result = verbalize_ontology(ontology_file, output_dir)
            if result:
                successful += 1
                print(f"✅ Successfully processed: {ontology_file.name}")
            else:
                failed += 1
                print(f"❌ Failed to process: {ontology_file.name}")
        except Exception as e:
            failed += 1
            print(f"❌ Error processing {ontology_file.name}: {e}")

    print(f"\n{'='*60}")
    print(f"Processing completed!")
    print(f"✅ Successful: {successful}")
    print(f"❌ Failed: {failed}")
    print(f"📁 Output directory: {output_dir.absolute()}")

if __name__ == "__main__":
    main()