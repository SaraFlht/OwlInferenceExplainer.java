#!/usr/bin/env python3
"""
visualize_ontology.py
---------------------
Visualize an OWL ontology (Turtle .ttl file) as an interactive HTML graph.

Usage:
    python visualize_ontology.py <path_to_ontology.ttl>

Example:
    python visualize_ontology.py ../src/main/resources/Thing_ada_rachel_heath_1868.ttl

Output:
    An HTML file (e.g., Thing_ada_rachel_heath_1868_graph.html) in the same directory as the input file.

Dependencies:
    pip install rdflib pyvis
"""
import sys
import os
from rdflib import Graph, RDF, RDFS, OWL, BNode
from pyvis.network import Network
from collections import defaultdict
import json
import re

def is_anonymous_node(node):
    """Check if a node is anonymous/blank node."""
    if isinstance(node, BNode):
        return True

    node_str = str(node)
    if node_str.startswith('n') and len(node_str) > 30:
        return True

    return False

def get_meaningful_label(uri_str):
    """Extract meaningful labels for URIs."""
    if '#' in uri_str:
        local_name = uri_str.split('#')[-1]
    elif '/' in uri_str:
        local_name = uri_str.split('/')[-1]
    else:
        local_name = uri_str

    return local_name

def is_individual(uri_str):
    """Check if a URI represents an individual person."""
    individuals = ['ada_rachel_heath_1868', 'john_heath', 'sarah_jacobs_1834', 'gwendoline_heath_1878', 'm135']
    return any(ind in uri_str for ind in individuals)

def is_class(uri_str):
    """Check if a URI represents a class."""
    classes = ['Person', 'Man', 'Woman', 'Male', 'Female', 'DomainEntity', 'Sex', 'Marriage', 'Ancestor']
    # Extract the local name from the URI
    if '#' in uri_str:
        local_name = uri_str.split('#')[-1]
    elif '/' in uri_str:
        local_name = uri_str.split('/')[-1]
    else:
        local_name = uri_str

    return local_name in classes

def is_property_relation(uri_str):
    """Check if a URI represents a property/relation starting with 'has' or 'is'."""
    local_name = get_meaningful_label(uri_str)
    return (local_name.startswith('has') or local_name.startswith('is')) and 'Property' not in uri_str

def get_hierarchical_level(uri_str):
    """Assign hierarchical levels - higher numbers = lower in hierarchy."""
    if 'DomainEntity' in uri_str:
        return 0  # Top level - DomainEntity
    elif any(cls in uri_str for cls in ['Person', 'Sex', 'Marriage', 'Ancestor', 'NamedIndividual', 'Class']):
        return 1  # Core classes at level 1
    elif any(cls in uri_str for cls in ['Male', 'Female', 'Man', 'Woman']):
        return 2  # Gender classes at level 2
    elif is_property_relation(uri_str):
        local_name = get_meaningful_label(uri_str)
        if local_name.startswith('has'):
            return 3  # Properties starting with 'has' at level 3
        elif local_name.startswith('is'):
            return 4  # Properties starting with 'is' at level 4
        else:
            return 3  # Other properties at level 3
    elif is_individual(uri_str):
        return 5  # Individuals at level 5
    elif any(year in uri_str.lower() for year in ['birthyear', 'deathyear']):
        return 6  # BirthYear/DeathYear at level 6
    elif uri_str.isdigit():
        return 7  # Literals at the very bottom
    else:
        return 3  # Other items at level 3

def assign_node_colors(uri_str):
    """Assign colors based on hierarchy order."""
    if 'DomainEntity' in uri_str:
        return "#2E8B57"  # Dark green for level 0
    elif is_property_relation(uri_str):
        local_name = get_meaningful_label(uri_str)
        if local_name.startswith('has'):
            return "#87CEEB"  # Light blue for level 3 (has properties)
        elif local_name.startswith('is'):
            return "#FFB6C1"  # Light pink for level 4 (is properties)
        else:
            return "#87CEEB"  # Light blue for other properties
    elif any(cls in uri_str for cls in ['Sex', 'Marriage', 'Person', 'Ancestor', 'NamedIndividual', 'Class']):
        return "#98FB98"  # Pale green for level 1
    elif any(cls in uri_str for cls in ['Male', 'Female', 'Man', 'Woman']):
        return "#EC4899"  # Pink for level 2
    elif is_individual(uri_str):
        return "#FF6B6B"  # Red for level 5
    elif any(year in uri_str.lower() for year in ['birthyear', 'deathyear']):
        return "#F39C12"  # Orange for level 6
    elif uri_str.isdigit():
        return "#9B59B6"  # Purple for level 7
    else:
        return "#87CEEB"  # Light blue for other properties

def assign_node_shape(uri_str):
    """Assign shapes based on hierarchy order."""
    if 'DomainEntity' in uri_str:
        return "square"  # Level 0
    elif is_property_relation(uri_str):
        local_name = get_meaningful_label(uri_str)
        if local_name.startswith('has'):
            return "diamond"  # Level 3 (has properties)
        elif local_name.startswith('is'):
            return "ellipse"  # Level 4 (is properties)
        else:
            return "diamond"  # Other properties
    elif any(cls in uri_str for cls in ['Sex', 'Marriage', 'Person', 'Ancestor', 'NamedIndividual', 'Class']):
        return "box"  # Level 1
    elif any(cls in uri_str for cls in ['Male', 'Female', 'Man', 'Woman']):
        return "hexagon"  # Level 2
    elif is_individual(uri_str):
        return "circle"  # Level 5
    elif any(year in uri_str.lower() for year in ['birthyear', 'deathyear']):
        return "triangle"  # Level 6
    elif uri_str.isdigit():
        return "dot"  # Level 7
    else:
        return "ellipse"  # Default

def get_node_size(uri_str):
    """Assign same size for all nodes."""
    return 25  # Same size for all nodes

def extract_property_domain_range(g):
    """Extract domain and range relationships from the ontology."""
    property_domains = defaultdict(set)
    property_ranges = defaultdict(set)

    # Extract domain relationships
    for subj, pred, obj in g:
        if str(pred) == 'http://www.w3.org/2000/01/rdf-schema#domain':
            property_domains[str(subj)].add(str(obj))

        elif str(pred) == 'http://www.w3.org/2000/01/rdf-schema#range':
            property_ranges[str(subj)].add(str(obj))

    return property_domains, property_ranges

def add_ontology_based_connections(graph_vis, g, added_nodes):
    """Add connections based on the actual ontology domain/range relationships."""

    property_domains, property_ranges = extract_property_domain_range(g)

    print(f"Found {len(property_domains)} properties with domain declarations")
    print(f"Found {len(property_ranges)} properties with range declarations")

    # Add domain connections
    for prop_uri, domains in property_domains.items():
        if prop_uri in added_nodes:
            for domain_uri in domains:
                if domain_uri not in added_nodes:
                    # Add the domain class if it doesn't exist
                    domain_label = get_meaningful_label(domain_uri)
                    graph_vis.add_node(
                        domain_uri,
                        label=domain_label,
                        color=assign_node_colors(domain_uri),
                        shape=assign_node_shape(domain_uri),
                        size=get_node_size(domain_uri),
                        level=get_hierarchical_level(domain_uri),
                        title=f"Level {get_hierarchical_level(domain_uri)}: {domain_label}\n{domain_uri}",
                        font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                    )
                    added_nodes.add(domain_uri)

                # Add domain edge
                graph_vis.add_edge(
                    prop_uri,
                    domain_uri,
                    label="domain",
                    title=f"{get_meaningful_label(prop_uri)} has domain {get_meaningful_label(domain_uri)}",
                    arrows="to",
                    color={'color': '#8E44AD'},
                    width=2,
                    dashes=True
                )

    # Add range connections
    for prop_uri, ranges in property_ranges.items():
        if prop_uri in added_nodes:
            for range_uri in ranges:
                if range_uri not in added_nodes:
                    # Add the range class if it doesn't exist
                    range_label = get_meaningful_label(range_uri)
                    graph_vis.add_node(
                        range_uri,
                        label=range_label,
                        color=assign_node_colors(range_uri),
                        shape=assign_node_shape(range_uri),
                        size=get_node_size(range_uri),
                        level=get_hierarchical_level(range_uri),
                        title=f"Level {get_hierarchical_level(range_uri)}: {range_label}\n{range_uri}",
                        font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                    )
                    added_nodes.add(range_uri)

                # Add range edge
                graph_vis.add_edge(
                    prop_uri,
                    range_uri,
                    label="range",
                    title=f"{get_meaningful_label(prop_uri)} has range {get_meaningful_label(range_uri)}",
                    arrows="to",
                    color={'color': '#E67E22'},
                    width=2,
                    dashes=True
                )

    return len(property_domains) + len(property_ranges)

def extract_subproperty_relationships(g):
    """Extract subPropertyOf relationships from the ontology."""
    subproperty_relationships = []

    for subj, pred, obj in g:
        if str(pred) == 'http://www.w3.org/2000/01/rdf-schema#subPropertyOf':
            subproperty_relationships.append((str(subj), str(obj)))

    return subproperty_relationships

def add_subproperty_connections(graph_vis, g, added_nodes):
    """Add subPropertyOf relationships to the graph."""

    subproperty_relationships = extract_subproperty_relationships(g)

    print(f"Found {len(subproperty_relationships)} subProperty relationships")

    for sub_prop, super_prop in subproperty_relationships:
        # Only add if both properties are in the graph
        if sub_prop in added_nodes and super_prop in added_nodes:
            graph_vis.add_edge(
                sub_prop,
                super_prop,
                label="subPropertyOf",
                title=f"{get_meaningful_label(sub_prop)} is subPropertyOf {get_meaningful_label(super_prop)}",
                arrows="to",
                color={'color': '#27AE60'},
                width=2,
                dashes=True
            )
        elif sub_prop in added_nodes and super_prop not in added_nodes:
            # Add the super property if it doesn't exist
            super_label = get_meaningful_label(super_prop)
            graph_vis.add_node(
                super_prop,
                label=super_label,
                color=assign_node_colors(super_prop),
                shape=assign_node_shape(super_prop),
                size=get_node_size(super_prop),
                level=get_hierarchical_level(super_prop),
                title=f"Level {get_hierarchical_level(super_prop)}: {super_label}\n{super_prop}",
                font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
            )
            added_nodes.add(super_prop)

            # Add the subproperty edge
            graph_vis.add_edge(
                sub_prop,
                super_prop,
                label="subPropertyOf",
                title=f"{get_meaningful_label(sub_prop)} is subPropertyOf {get_meaningful_label(super_prop)}",
                arrows="to",
                color={'color': '#27AE60'},
                width=2,
                dashes=True
            )

    return len(subproperty_relationships)

def fix_individual_labels_in_html(html_file_path, individual_labels):
    """Fix individual node labels in the HTML file and add custom labels."""
    try:
        with open(html_file_path, 'r', encoding='utf-8') as file:
            html_content = file.read()

        # Fix the node labels in the JavaScript nodes array
        # Find the nodes array and replace full URIs with empty strings for individuals
        def replace_individual_labels(match):
            nodes_content = match.group(1)
            # Replace labels for individual nodes
            for node_id, label in individual_labels.items():
                # Escape the node ID for regex
                escaped_id = re.escape(node_id)
                # Replace the label with empty string
                pattern = f'("id": "{escaped_id}"[^}}]*"label": ")[^"]*(")'
                nodes_content = re.sub(pattern, r'\1\2', nodes_content)
            return f'nodes = new vis.DataSet([{nodes_content}]);'

        # Apply the replacement
        html_content = re.sub(r'nodes = new vis\.DataSet\(\[(.*?)\]\);', replace_individual_labels, html_content, flags=re.DOTALL)

        # Add custom CSS and JavaScript for labels
        custom_script = f"""
        <script>
        // Add labels below individual nodes
        network.on("stabilizationIterationsDone", function() {{
            setTimeout(function() {{
                drawCustomLabels();
            }}, 500);
        }});
        
        network.on("zoom", function() {{
            drawCustomLabels();
        }});
        
        network.on("dragEnd", function() {{
            drawCustomLabels();
        }});
        
        function drawCustomLabels() {{
            // Remove existing labels
            var existingLabels = document.querySelectorAll('.custom-node-label');
            existingLabels.forEach(function(label) {{
                label.remove();
            }});
            
            var nodeLabels = {json.dumps(individual_labels)};
            var container = document.getElementById('mynetwork');
            
            for (var nodeId in nodeLabels) {{
                try {{
                    var position = network.getPositions([nodeId])[nodeId];
                    if (position) {{
                        var canvasPosition = network.canvasToDOM(position);
                        
                        var label = document.createElement('div');
                        label.className = 'custom-node-label';
                        label.innerHTML = nodeLabels[nodeId];
                        label.style.position = 'absolute';
                        label.style.left = (canvasPosition.x - 60) + 'px';
                        label.style.top = (canvasPosition.y + 40) + 'px';
                        label.style.width = '120px';
                        label.style.textAlign = 'center';
                        label.style.fontSize = '12px';
                        label.style.fontWeight = 'bold';
                        label.style.color = '#2C3E50';
                        label.style.backgroundColor = 'rgba(255, 255, 255, 0.9)';
                        label.style.padding = '3px 6px';
                        label.style.borderRadius = '4px';
                        label.style.border = '1px solid #ddd';
                        label.style.pointerEvents = 'none';
                        label.style.zIndex = '1000';
                        label.style.boxShadow = '0 1px 3px rgba(0,0,0,0.1)';
                        
                        container.appendChild(label);
                    }}
                }} catch(e) {{
                    console.log('Error drawing label for node:', nodeId, e);
                }}
            }}
        }}
        </script>
        """

        # Insert the custom script before the closing body tag
        html_content = html_content.replace('</body>', custom_script + '\n</body>')

        with open(html_file_path, 'w', encoding='utf-8') as file:
            file.write(html_content)

        print(f"Fixed individual labels and added custom labels for {len(individual_labels)} individuals")

    except Exception as e:
        print(f"Error fixing individual labels: {e}")

def find_ancestors(g):
    """Find individuals who are ancestors (have descendants)."""
    ancestors = set()

    # Look for individuals who have children (are parents)
    for subj, pred, obj in g:
        if is_individual(str(subj)) and is_individual(str(obj)):
            pred_label = get_meaningful_label(str(pred))
            # If someone is a parent, they are an ancestor
            if pred_label in ['isFatherOf', 'isMotherOf', 'isParentOf']:
                ancestors.add(str(subj))

    print(f"Found ancestors: {[get_meaningful_label(a) for a in ancestors]}")
    return ancestors

def collect_inverse_properties(g):
    """Collect all inverse property relationships."""
    inverse_pairs = []

    for subj, pred, obj in g:
        if str(pred) == 'http://www.w3.org/2002/07/owl#inverseOf':
            subj_str = str(subj)
            obj_str = str(obj)

            # Only include if both are has/is properties
            if is_property_relation(subj_str) and is_property_relation(obj_str):
                inverse_pairs.append((subj_str, obj_str))
                print(f"Found inverse pair: {get_meaningful_label(subj_str)} <-> {get_meaningful_label(obj_str)}")

    return inverse_pairs

def visualize_ontology(ttl_path):
    """Visualize an ontology file and save as HTML."""
    if not os.path.isfile(ttl_path):
        print(f"File not found: {ttl_path}")
        return False

    # Load ontology
    g = Graph()
    g.parse(ttl_path, format="turtle")

    # Find ancestors and inverse properties
    ancestors = find_ancestors(g)
    inverse_pairs = collect_inverse_properties(g)

    # Create pyvis network with hierarchical layout
    graph_vis = Network(
        height="900px",
        width="100%",
        directed=True,
        notebook=False,
        bgcolor="#ffffff",
        font_color="black"
    )

    # Track added nodes and relationships
    added_nodes = set()
    individual_relationships = []
    class_relationships = []
    individual_labels = {}  # Store labels for individuals

    # First pass: collect all relevant triples
    for subj, pred, obj in g:
        # Skip anonymous nodes
        if is_anonymous_node(subj) or is_anonymous_node(obj):
            continue

        subj_str = str(subj)
        pred_str = str(pred)
        obj_str = str(obj)

        # Focus on individuals and their relationships
        if is_individual(subj_str) or is_individual(obj_str):
            individual_relationships.append((subj_str, pred_str, obj_str))

        # Include class relationships for context
        if is_class(subj_str) or is_class(obj_str):
            class_relationships.append((subj_str, pred_str, obj_str))

    print(f"Individual relationships: {len(individual_relationships)}")
    print(f"Class relationships: {len(class_relationships)}")

    # Process individual relationships
    individual_counter = 0
    individual_y_step = 200  # Increased step for better separation
    individual_x_step = 150  # Add horizontal separation
    individual_y_base = 0

    for subj_str, pred_str, obj_str in individual_relationships:
        subj_label = get_meaningful_label(subj_str)
        obj_label = get_meaningful_label(obj_str)
        pred_label = get_meaningful_label(pred_str)

        # Add nodes
        if subj_str not in added_nodes:
            node_size = get_node_size(subj_str)
            node_kwargs = {
                'color': assign_node_colors(subj_str),
                'shape': assign_node_shape(subj_str),
                'size': node_size,
                'level': get_hierarchical_level(subj_str),
                'title': f"Level {get_hierarchical_level(subj_str)}: {subj_label}\n{subj_str}",
                'font': {'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
            }

            if is_individual(subj_str):
                # Use a space as label for individuals - will be replaced later
                node_kwargs['label'] = ' '
                # Zigzag pattern: alternating up/down and left/right
                y_offset = (individual_counter % 2 * 2 - 1) * individual_y_step  # Alternates between -120 and +120
                x_offset = (individual_counter // 2) * individual_x_step  # Moves right every 2 individuals
                node_kwargs['x'] = x_offset
                node_kwargs['y'] = individual_y_base + y_offset
                individual_labels[subj_str] = subj_label
                individual_counter += 1
            else:
                node_kwargs['label'] = subj_label

            graph_vis.add_node(subj_str, **node_kwargs)
            added_nodes.add(subj_str)

        if obj_str not in added_nodes:
            node_size = get_node_size(obj_str)
            node_kwargs = {
                'color': assign_node_colors(obj_str),
                'shape': assign_node_shape(obj_str),
                'size': node_size,
                'level': get_hierarchical_level(obj_str),
                'title': f"Level {get_hierarchical_level(obj_str)}: {obj_label}\n{obj_str}",
                'font': {'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
            }

            if is_individual(obj_str):
                # Use a space as label for individuals - will be replaced later
                node_kwargs['label'] = ' '
                y_offset = (individual_counter % 2 * 2 - 1) * individual_y_step
                x_offset = (individual_counter // 2) * individual_x_step
                node_kwargs['x'] = x_offset
                node_kwargs['y'] = individual_y_base + y_offset
                individual_labels[obj_str] = obj_label
                individual_counter += 1
            else:
                node_kwargs['label'] = obj_label

            graph_vis.add_node(obj_str, **node_kwargs)
            added_nodes.add(obj_str)

        # Add edge
        graph_vis.add_edge(
            subj_str,
            obj_str,
            label=pred_label,
            title=f"{subj_label} --{pred_label}--> {obj_label}",
            arrows="to",
            color={'color': '#2C3E50'},
            width=1
        )

    # Add key class relationships for context - INCLUDING ANCESTOR and ALL has/is properties
    key_classes = ['Person', 'Man', 'Woman', 'Male', 'Female', 'DomainEntity', 'Sex', 'Ancestor']
    for subj_str, pred_str, obj_str in class_relationships:
        subj_label = get_meaningful_label(subj_str)
        obj_label = get_meaningful_label(obj_str)
        pred_label = get_meaningful_label(pred_str)

        # Include if it involves key classes OR if it's a has/is property
        if (any(cls in subj_str for cls in key_classes) or
                any(cls in obj_str for cls in key_classes) or
                is_property_relation(subj_str) or
                is_property_relation(obj_str) or
                is_property_relation(pred_str)):

            # Add nodes if not already added
            if subj_str not in added_nodes:
                node_size = 25 if is_individual(subj_str) else get_node_size(subj_str)
                node_kwargs = {
                    'color': assign_node_colors(subj_str),
                    'shape': assign_node_shape(subj_str),
                    'size': node_size,
                    'level': get_hierarchical_level(subj_str),
                    'title': f"Level {get_hierarchical_level(subj_str)}: {subj_label}\n{subj_str}",
                    'font': {'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                }

                if is_individual(subj_str):
                    node_kwargs['label'] = ' '
                    individual_labels[subj_str] = subj_label
                else:
                    node_kwargs['label'] = subj_label

                graph_vis.add_node(subj_str, **node_kwargs)
                added_nodes.add(subj_str)

            if obj_str not in added_nodes:
                node_size = 25 if is_individual(obj_str) else get_node_size(obj_str)
                node_kwargs = {
                    'color': assign_node_colors(obj_str),
                    'shape': assign_node_shape(obj_str),
                    'size': node_size,
                    'level': get_hierarchical_level(obj_str),
                    'title': f"Level {get_hierarchical_level(obj_str)}: {obj_label}\n{obj_str}",
                    'font': {'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                }

                if is_individual(obj_str):
                    node_kwargs['label'] = ' '
                    individual_labels[obj_str] = obj_label
                else:
                    node_kwargs['label'] = obj_label

                graph_vis.add_node(obj_str, **node_kwargs)
                added_nodes.add(obj_str)

            # Add edge for class relationships
            graph_vis.add_edge(
                subj_str,
                obj_str,
                label=pred_label,
                title=f"{subj_label} --{pred_label}--> {obj_label}",
                arrows="to",
                color={'color': '#7F8C8D'},
                width=1,
                dashes=True  # Dashed lines for class relationships
            )

    # Add ontology-based connections
    domain_range_count = add_ontology_based_connections(graph_vis, g, added_nodes)
    subproperty_count = add_subproperty_connections(graph_vis, g, added_nodes)

    print(f"Added {domain_range_count} domain/range connections from ontology")
    print(f"Added {subproperty_count} subProperty connections from ontology")

    # Add inverse property relationships
    for prop1, prop2 in inverse_pairs:
        # Ensure both properties are added as nodes
        for prop_uri in [prop1, prop2]:
            if prop_uri not in added_nodes:
                prop_label = get_meaningful_label(prop_uri)
                graph_vis.add_node(
                    prop_uri,
                    label=prop_label,
                    color=assign_node_colors(prop_uri),
                    shape=assign_node_shape(prop_uri),
                    size=get_node_size(prop_uri),
                    level=get_hierarchical_level(prop_uri),
                    title=f"Level {get_hierarchical_level(prop_uri)}: {prop_label}\n{prop_uri}",
                    font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                )
                added_nodes.add(prop_uri)

        # Add undirected edge showing inverse relationship
        graph_vis.add_edge(
            prop1,
            prop2,
            label="inverseOf",
            title=f"{get_meaningful_label(prop1)} is inverse of {get_meaningful_label(prop2)}",
            arrows="",  # No arrows for bidirectional relationship
            color={'color': '#FF6B35'},
            width=2,
            dashes=False
        )

    # Ensure Ancestor class is always included
    ancestor_uri = "http://www.example.com/genealogy.owl#Ancestor"
    if ancestor_uri not in added_nodes:
        print("Adding Ancestor class explicitly")
        graph_vis.add_node(
            ancestor_uri,
            label="Ancestor",
            color=assign_node_colors(ancestor_uri),
            shape=assign_node_shape(ancestor_uri),
            size=get_node_size(ancestor_uri),
            level=get_hierarchical_level(ancestor_uri),
            title=f"Level {get_hierarchical_level(ancestor_uri)}: Ancestor\n{ancestor_uri}",
            font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
        )
        added_nodes.add(ancestor_uri)

    # Now add inferred type relationships for individuals
    individuals = ['ada_rachel_heath_1868', 'john_heath', 'sarah_jacobs_1834', 'gwendoline_heath_1878', 'm135']

    # Add type relationships for ALL individuals, regardless of what's in the ontology
    for individual in individuals:
        individual_uri = f"http://www.example.com/genealogy.owl#{individual}"

        # Always add Person type for all individuals
        person_uri = "http://www.example.com/genealogy.owl#Person"
        if person_uri not in added_nodes:
            graph_vis.add_node(
                person_uri,
                label="Person",
                color=assign_node_colors(person_uri),
                shape=assign_node_shape(person_uri),
                size=get_node_size(person_uri),
                level=get_hierarchical_level(person_uri),
                title=f"Level {get_hierarchical_level(person_uri)}: Person\n{person_uri}",
                font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
            )
            added_nodes.add(person_uri)

        # Add type edge to Person for all individuals
        graph_vis.add_edge(
            individual_uri,
            person_uri,
            label="type",
            title=f"{individual} is a Person",
            arrows="to",
            color={'color': '#2E8B57'},
            width=2
        )

        # Add gender-specific types
        if 'ada_rachel' in individual or 'sarah_jacobs' in individual or 'gwendoline' in individual:
            # Female individuals
            for class_type in ['Woman', 'Female']:
                class_uri = f"http://www.example.com/genealogy.owl#{class_type}"
                if class_uri not in added_nodes:
                    graph_vis.add_node(
                        class_uri,
                        label=class_type,
                        color=assign_node_colors(class_uri),
                        shape=assign_node_shape(class_uri),
                        size=get_node_size(class_uri),
                        level=get_hierarchical_level(class_uri),
                        title=f"Level {get_hierarchical_level(class_uri)}: {class_type}\n{class_uri}",
                        font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                    )
                    added_nodes.add(class_uri)

                # Add type edge
                graph_vis.add_edge(
                    individual_uri,
                    class_uri,
                    label="type",
                    title=f"{individual} is a {class_type}",
                    arrows="to",
                    color={'color': '#E74C3C'},
                    width=2
                )

        elif 'john_heath' in individual or 'm135' in individual:
            # Male individuals
            for class_type in ['Man', 'Male']:
                class_uri = f"http://www.example.com/genealogy.owl#{class_type}"
                if class_uri not in added_nodes:
                    graph_vis.add_node(
                        class_uri,
                        label=class_type,
                        color=assign_node_colors(class_uri),
                        shape=assign_node_shape(class_uri),
                        size=get_node_size(class_uri),
                        level=get_hierarchical_level(class_uri),
                        title=f"Level {get_hierarchical_level(class_uri)}: {class_type}\n{class_uri}",
                        font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                    )
                    added_nodes.add(class_uri)

                # Add type edge
                graph_vis.add_edge(
                    individual_uri,
                    class_uri,
                    label="type",
                    title=f"{individual} is a {class_type}",
                    arrows="to",
                    color={'color': '#3498DB'},
                    width=2
                )

        # Add Ancestor relationship only for individuals who are actually ancestors
        if individual_uri in ancestors:
            if ancestor_uri not in added_nodes:
                graph_vis.add_node(
                    ancestor_uri,
                    label="Ancestor",
                    color=assign_node_colors(ancestor_uri),
                    shape=assign_node_shape(ancestor_uri),
                    size=get_node_size(ancestor_uri),
                    level=get_hierarchical_level(ancestor_uri),
                    title=f"Level {get_hierarchical_level(ancestor_uri)}: Ancestor\n{ancestor_uri}",
                    font={'size': 12, 'color': 'black', 'align': 'bottom', 'face': 'arial'}
                )
                added_nodes.add(ancestor_uri)

            # Add type edge to Ancestor only for actual ancestors
            graph_vis.add_edge(
                individual_uri,
                ancestor_uri,
                label="type",
                title=f"{individual} is an Ancestor",
                arrows="to",
                color={'color': '#9B59B6'},
                width=2
            )

    print(f"Added {len(added_nodes)} nodes to the graph")
    print(f"Added {len(inverse_pairs)} inverse property relationships")

    # Print level distribution
    level_counts = defaultdict(int)
    for node_id in added_nodes:
        level = get_hierarchical_level(node_id)
        level_counts[level] += 1

    print("\nHierarchical levels:")
    for level in sorted(level_counts.keys()):
        print(f"  Level {level}: {level_counts[level]} nodes")

    # Configure hierarchical layout with improved spacing to prevent overlapping
    graph_vis.set_options("""
    {
      "layout": {
        "hierarchical": {
          "enabled": true,
          "levelSeparation": 120,
          "nodeSpacing": 80,
          "treeSpacing": 150,
          "blockShifting": true,
          "edgeMinimization": true,
          "parentCentralization": true,
          "direction": "UD",
          "sortMethod": "directed",
          "shakeTowards": "leaves"
        }
      },
      "physics": {
        "enabled": true,
        "hierarchicalRepulsion": {
          "centralGravity": 0.0,
          "springLength": 120,
          "springConstant": 0.01,
          "nodeDistance": 80,
          "damping": 0.09
        },
        "maxVelocity": 50,
        "minVelocity": 0.1,
        "solver": "hierarchicalRepulsion",
        "stabilization": {
          "enabled": true,
          "iterations": 1000,
          "updateInterval": 25
        }
      },
      "groups": {
        "hasProperties": {
          "level": 3,
          "color": "#87CEEB"
        },
        "isProperties": {
          "level": 4,
          "color": "#FFB6C1"
        }
      },
      "edges": {
        "arrows": {
          "to": {"enabled": true, "scaleFactor": 1.2}
        },
        "smooth": {"enabled": true, "type": "curvedCW"},
        "font": {"size": 12, "align": "middle"},
        "selectionWidth": 4,
        "hoverWidth": 3,
        "width": 1
      },
      "nodes": {
        "font": {"size": 14, "face": "arial"},
        "borderWidth": 2,
        "shadow": true,
        "chosen": {
          "node": true,
          "label": false
        }
      },
      "interaction": {
        "dragNodes": true,
        "dragView": true,
        "zoomView": true,
        "selectConnectedEdges": true,
        "hoverConnectedEdges": true
      }
    }
    """)

    # Output HTML file
    base = os.path.splitext(os.path.basename(ttl_path))[0]
    out_path = os.path.join(os.path.dirname(ttl_path), f"{base}_interactive_hierarchical_graph.html")

    try:
        graph_vis.show(out_path, notebook=False)

        # Fix individual labels and add custom labels after generating the HTML
        fix_individual_labels_in_html(out_path, individual_labels)

        print(f"Interactive hierarchical graph saved to: {out_path}")
        return True
    except Exception as e:
        print(f"Error saving graph: {e}")
        return False

def main():
    """Main function for IDE execution."""
    # Get the directory where this script is located
    script_dir = os.path.dirname(os.path.abspath(__file__))
    # Go up one level and then to src/main/resources
    ttl_path = os.path.join(script_dir, "..", "src", "main", "resources", "Thing_ada_rachel_heath_1868.ttl")

    print(f"Visualizing ontology: {ttl_path}")
    success = visualize_ontology(ttl_path)

    if success:
        print("Interactive hierarchical graph completed successfully!")
    else:
        print("Visualization failed!")

if __name__ == "__main__":
    # Check if command line argument is provided
    if len(sys.argv) == 2:
        # Use command line argument
        ttl_path = sys.argv[1]
        visualize_ontology(ttl_path)
    else:
        # Use main function for IDE execution
        main()