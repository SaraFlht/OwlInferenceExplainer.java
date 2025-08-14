import os
import time
import sys
import pandas as pd
import json
from datetime import datetime
import openai
from dotenv import load_dotenv
from tqdm import tqdm
import logging
from pathlib import Path
import re

# Navigate to project root
script_dir = Path(__file__).resolve().parent
project_root = script_dir.parent.parent
os.chdir(project_root)

print(f"Working directory set to: {os.getcwd()}")

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('sparql_nl_explanations_processing.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

load_dotenv()

# Configuration
CONFIG = {
    'temperature': 0.0,
    'max_tokens': 4096,
    'top_p': 1.0,
    'presence_penalty': 0.0,
    'frequency_penalty': 0.0,
    'checkpoint_frequency': 50,
    'api_delay': 0.1,
    'max_retries': 3,
    'retry_delay': 2.0,
    'explanations_json_path': 'output/FamilyOWL/1hop/Explanations.json'  # Path to your explanations
}

# Set your API key
openai_api_key = os.getenv("OPENAI_API_KEY")
if not openai_api_key:
    raise ValueError("OPENAI_API_KEY not found in environment variables")

# Initialize OpenAI client
client = openai.OpenAI(api_key=openai_api_key)

# Enhanced prompt templates
PROMPT_TEMPLATES = {
    'sparql_to_nl_binary': """Assume you have to rephrase the following SPARQL query as a true/false (binary) natural-language question for non-technical humans. Do not include birth dates or birth years in the question.
Use the {language_string} language. SPARQL query: 
{sparql}""",

    'sparql_to_nl_membership': """Assume you have to rephrase the following SPARQL query as a multiple or single choice natural-language question for non-technical humans. Avoid technical phrases like 'type of entity' or 'rdf:type'. Ask what class or classes the individual belongs to. Do not include birth dates or birth years in the question. 
Use the {language_string} language. SPARQL query:
{sparql}""",

    'sparql_to_nl_property': """Assume you have to rephrase the following SPARQL query as a multiple or single choice natural-language question for non-technical humans. Describe the relationship or attributes involved, and avoid SPARQL or ontology jargon. Do not include birth dates or birth years in the question.
Use the {language_string} language. SPARQL query:
{sparql}"""
}

def normalize_sparql(sparql):
    """Normalize SPARQL query for comparison"""
    # Remove extra whitespace and normalize
    return ' '.join(sparql.split()).strip()

def extract_subject_from_sparql(sparql):
    """Extract subject from SPARQL query"""
    # Look for pattern like <...subject...> in WHERE clause
    where_match = re.search(r'WHERE\s*\{([^}]+)\}', sparql, re.IGNORECASE)
    if where_match:
        where_clause = where_match.group(1).strip()
        # Find first URI in angle brackets
        subject_match = re.search(r'<([^>]+)>', where_clause)
        if subject_match:
            uri = subject_match.group(1)
            # Extract entity name from URI
            if '#' in uri:
                return uri.split('#')[-1]
            elif '/' in uri:
                return uri.split('/')[-1]
            return uri
    return None

def extract_predicate_from_sparql(sparql):
    """Extract predicate from SPARQL query"""
    # Look for rdf:type or other predicates
    if 'rdf:type' in sparql:
        return 'rdf:type'

    # Look for second URI in WHERE clause
    where_match = re.search(r'WHERE\s*\{([^}]+)\}', sparql, re.IGNORECASE)
    if where_match:
        where_clause = where_match.group(1).strip()
        # Find all URIs and take the second one as predicate
        uris = re.findall(r'<([^>]+)>', where_clause)
        if len(uris) >= 2:
            predicate_uri = uris[1]
            if '#' in predicate_uri:
                return predicate_uri.split('#')[-1]
            elif '/' in predicate_uri:
                return predicate_uri.split('/')[-1]
            return predicate_uri
    return 'rdf:type'  # Default fallback

def get_explanation_with_longest_tag(explanations_list):
    """Get explanation with the longest tag from a list of explanations"""
    if not explanations_list:
        return [], 0, []

    best_explanation_steps = []
    max_tag_length = 0

    for explanation in explanations_list:
        if isinstance(explanation, list):
            # Find the tag in this explanation
            tag_items = [item for item in explanation if isinstance(item, str) and item.startswith("TAG:")]
            if tag_items:
                tag = tag_items[0].replace("TAG:", "")
                if len(tag) > max_tag_length:
                    max_tag_length = len(tag)
                    # Get individual reasoning steps (exclude TAG)
                    best_explanation_steps = [
                        item for item in explanation
                        if not (isinstance(item, str) and item.startswith("TAG:"))
                    ]

    return best_explanation_steps, max_tag_length, explanations_list

def find_explanations_by_sparql_and_answers(sparql_query, row, explanations_data):
    """Find explanations using SPARQL query and answer data"""

    explanations_found = []

    # For MC questions, get all possible answers
    if row.get('Answer Type') == 'MC':
        answer_column = row.get('Answer', '')
        if ';' in answer_column:
            answers = [ans.strip() for ans in answer_column.split(';')]
        elif ',' in answer_column:
            answers = [ans.strip() for ans in answer_column.split(',')]
        else:
            answers = [answer_column.strip()]

        # Parse SPARQL to get subject/predicate
        subject = extract_subject_from_sparql(sparql_query)
        predicate = extract_predicate_from_sparql(sparql_query)

        if not subject or not predicate:
            logger.warning(f"Could not extract subject/predicate from SPARQL: {sparql_query}")
            return explanations_found

        print(f"   🔍 Looking for explanations for answers: {answers}")
        print(f"   📝 Subject: {subject}, Predicate: {predicate}")

        # Find explanations for each answer
        for answer in answers:
            # FIXED: Ensure no extra spaces and try multiple variations
            answer = answer.strip()

            # Try exact match first
            triple_key = f"{subject}|{predicate}|{answer}"
            print(f"   🔑 Trying triple key: '{triple_key}'")

            if triple_key in explanations_data:
                explanation_info = explanations_data[triple_key]
                print(f"   ✅ Found exact match for: {answer}")
            else:
                # Try alternative formats if exact match fails
                alternative_keys = [
                    f"{subject}|{predicate}|{answer}",  # Original
                    f"{subject}|rdf:type|{answer}",     # Force rdf:type if predicate parsing failed
                ]

                explanation_info = None
                matched_key = None

                for alt_key in alternative_keys:
                    print(f"   🔑 Trying alternative key: '{alt_key}'")
                    if alt_key in explanations_data:
                        explanation_info = explanations_data[alt_key]
                        matched_key = alt_key
                        print(f"   ✅ Found alternative match: {alt_key}")
                        break

                if not explanation_info:
                    print(f"   ❌ No explanation found for answer: '{answer}'")
                    print(f"   📋 Available keys for {subject}:")
                    available_keys = [key for key in explanations_data.keys() if key.startswith(f"{subject}|")]
                    for key in available_keys[:5]:  # Show first 5 matches
                        print(f"        - {key}")
                    continue

                triple_key = matched_key

            # Get the longest explanation for THIS specific answer
            steps, tag_length, all_explanations = get_explanation_with_longest_tag(
                explanation_info.get('explanations', [])
            )

            if steps and tag_length > 0:
                explanations_found.append({
                    'triple_key': triple_key,
                    'explanation_info': explanation_info,
                    'answer': answer,
                    'steps': steps,
                    'tag_length': tag_length,
                    'explanation_count': explanation_info.get('explanationCount', 0)
                })
                print(f"   ✅ Added explanation for {answer}: {tag_length} tag length, {len(steps)} steps")
            else:
                print(f"   ⚠️  Found explanation entry for {answer} but no valid steps/tags")

    else:
        # For binary questions, use the original SPARQL matching method
        normalized_query = normalize_sparql(sparql_query)

        for triple_key, explanation_info in explanations_data.items():
            sparql_queries = explanation_info.get('sparqlQueries', [])

            for stored_query in sparql_queries:
                if normalized_query == normalize_sparql(stored_query):
                    steps, tag_length, all_explanations = get_explanation_with_longest_tag(
                        explanation_info.get('explanations', [])
                    )

                    if steps and tag_length > 0:
                        explanations_found.append({
                            'triple_key': triple_key,
                            'explanation_info': explanation_info,
                            'answer': triple_key.split('|')[2] if '|' in triple_key else 'Unknown',
                            'steps': steps,
                            'tag_length': tag_length,
                            'explanation_count': explanation_info.get('explanationCount', 0)
                        })
                    break

    print(f"   📊 Total explanations found: {len(explanations_found)}")
    return explanations_found

def combine_all_explanation_steps(explanations_found):
    """Combine steps from all explanations found (one per answer for MC)"""
    if not explanations_found:
        return None

    # For MC: combine all explanations
    # For Binary: should only have one explanation
    all_steps = []
    total_tag_length = 0
    total_explanation_count = 0
    triple_keys = []

    for exp_data in explanations_found:
        all_steps.extend(exp_data['steps'])
        total_tag_length += exp_data['tag_length']
        total_explanation_count += exp_data['explanation_count']
        triple_keys.append(f"{exp_data['answer']}({exp_data['tag_length']})")

    return {
        'steps': all_steps,
        'tag_length': total_tag_length,  # Sum of all tag lengths
        'explanation_count': total_explanation_count,
        'triple_keys': "; ".join(triple_keys),  # Show all answers with their tag lengths
        'answer_count': len(explanations_found)
    }

def translate_explanation_steps_individually(steps, client, gen_params):
    """Translate each explanation step individually to natural language"""

    if not steps:
        return "No explanation steps available"

    translated_steps = []

    for step in steps:
        if not step or not step.strip():
            continue

        # Prompt for individual step translation
        step_prompt = f"""Convert this single logical reasoning step into a clear, simple sentence. Do not add conclusions or interpretations, just translate the facts stated. Use full names instead of technical identifiers.

Logical step: {step}

Simple sentence:"""

        try:
            messages = [{"role": "user", "content": step_prompt}]
            response = client.chat.completions.create(
                model="gpt-4o-mini",
                messages=messages,
                **gen_params
            )

            translated_step = response.choices[0].message.content.strip()

            # Clean up the response (remove quotes, extra formatting)
            translated_step = translated_step.strip('"\'')
            if translated_step and not translated_step.startswith('[ERROR]'):
                translated_steps.append(translated_step)
            else:
                # Fallback to original step
                translated_steps.append(step)

        except Exception as e:
            logger.warning(f"Failed to translate step '{step}': {str(e)}")
            # Fallback to original step
            translated_steps.append(step)

        # Small delay between API calls
        time.sleep(0.1)

    return " ".join(translated_steps)

def make_api_call_with_retry(client, messages, gen_params, max_retries=3):
    """Make API call with retry logic."""
    for attempt in range(max_retries):
        try:
            response = client.chat.completions.create(
                model="gpt-4o-mini",
                messages=messages,
                **gen_params
            )
            return response.choices[0].message.content, get_model_version_info(response)
        except Exception as e:
            logger.warning(f"API call attempt {attempt + 1} failed: {str(e)}")
            if attempt < max_retries - 1:
                time.sleep(CONFIG['retry_delay'] * (attempt + 1))
            else:
                logger.error(f"All {max_retries} API call attempts failed")
                return f"[ERROR] {str(e)}", "Error - Unable to retrieve"

def get_model_version_info(response):
    """Extract model version information from response."""
    model_version = "Unknown"
    if hasattr(response, 'model'):
        model_version = response.model
    if hasattr(response, 'system_fingerprint'):
        model_version += f" (fingerprint: {response.system_fingerprint})"
    return model_version

def get_sparql_to_nl_prompt_template(answer_type, task_type=None):
    """Get appropriate prompt template for SPARQL to NL conversion"""
    if answer_type == "BIN":
        return PROMPT_TEMPLATES['sparql_to_nl_binary']
    elif answer_type == "MC":
        if task_type and task_type.strip() == "Membership":
            return PROMPT_TEMPLATES['sparql_to_nl_membership']
        else:
            return PROMPT_TEMPLATES['sparql_to_nl_property']
    else:
        return PROMPT_TEMPLATES['sparql_to_nl_binary']  # Default fallback

def validate_dataframe(df):
    """Validate input DataFrame has required columns."""
    required_columns = ['SPARQL Query']
    missing_cols = [col for col in required_columns if col not in df.columns]
    if missing_cols:
        raise ValueError(f"CSV must contain columns: {missing_cols}")

    df['Language'] = df.get('Language', 'English')
    df['Answer Type'] = df.get('Answer Type', 'BIN')

    logger.info(f"Loaded DataFrame with {len(df)} rows")
    return df

def save_checkpoint(df, logs, timestamp, checkpoint_num):
    """Save intermediate results."""
    checkpoint_dir = Path(f"output/FamilyOWL/1hop/checkpoints_sparql_nl_explanations_{timestamp}")
    checkpoint_dir.mkdir(parents=True, exist_ok=True)

    df.to_csv(checkpoint_dir / f"checkpoint_{checkpoint_num}_questions.csv", index=False)
    pd.DataFrame(logs).to_csv(checkpoint_dir / f"checkpoint_{checkpoint_num}_log.csv", index=False)
    logger.info(f"Checkpoint {checkpoint_num} saved")

def create_output_directory(timestamp):
    """Create organized output directory."""
    output_dir = Path(f"sparql_nl_explanations_output_{timestamp}")
    output_dir.mkdir(exist_ok=True)
    return output_dir

def main():
    print("🚀 SPARQL to NL + Multi-Answer Step-by-Step Explanations Converter")
    print("=" * 70)

    # Load explanations data
    print(f"📊 Loading explanations from: {CONFIG['explanations_json_path']}")
    try:
        with open(CONFIG['explanations_json_path'], 'r', encoding='utf-8') as f:
            explanations_data = json.load(f)
        print(f"✅ Loaded {len(explanations_data)} explanations")
    except FileNotFoundError:
        print(f"❌ Explanations file not found: {CONFIG['explanations_json_path']}")
        return
    except Exception as e:
        print(f"❌ Error loading explanations: {str(e)}")
        return

    # Load and validate SPARQL data
    try:
        df = pd.read_csv("output/FamilyOWL/1hop/SPARQL_questions_sampling.csv")
        df = validate_dataframe(df)
    except FileNotFoundError:
        logger.error("SPARQL questions CSV file not found")
        return
    except Exception as e:
        logger.error(f"Error loading CSV: {str(e)}")
        return

    # Setup output
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_dir = create_output_directory(timestamp)
    logs = []

    # Add new columns for results
    df['Question'] = ""
    df['Explanation_NL'] = ""
    df['Explanation_Found'] = False
    df['Explanation_Tag_Length'] = 0
    df['Explanation_Count'] = 0
    df['Explanation_Steps_Raw'] = ""
    df['Triple_Key'] = ""
    df['Answer_Count'] = 0

    # Track statistics
    stats = {
        'total': len(df),
        'processed': 0,
        'errors': 0,
        'explanations_found': 0,
        'mc_questions': 0,
        'bin_questions': 0,
        'start_time': time.time()
    }

    # Generation parameters
    gen_params = {k: v for k, v in CONFIG.items()
                  if k in ['temperature', 'max_tokens', 'top_p', 'presence_penalty', 'frequency_penalty']}

    # Process each SPARQL question with progress bar
    for idx, row in tqdm(df.iterrows(), total=len(df), desc="Processing SPARQL queries"):
        try:
            sparql = row["SPARQL Query"]
            language_string = row.get("Language", "English")
            answer_type = row.get("Answer Type", "BIN")
            task_type = row.get("Task Type", "")

            print(f"\n🔍 Processing question #{idx+1}")
            print(f"   SPARQL: {sparql[:100]}...")
            print(f"   Answer Type: {answer_type}")

            if answer_type == "MC":
                stats['mc_questions'] += 1
                print(f"   Answers: {row.get('Answer', 'N/A')}")
            else:
                stats['bin_questions'] += 1

            # Find explanations by SPARQL query and answers
            explanations_found = find_explanations_by_sparql_and_answers(sparql, row, explanations_data)

            print(f"   📝 Found {len(explanations_found)} explanations")
            for exp in explanations_found:
                print(f"      - {exp['answer']}: tag length {exp['tag_length']}")

            # Combine all explanation steps
            combined_explanation_info = combine_all_explanation_steps(explanations_found)

            explanation_nl = ""
            if combined_explanation_info and combined_explanation_info['steps']:
                print(f"   📝 Total steps: {len(combined_explanation_info['steps'])}")
                print(f"   📝 Combined tag length: {combined_explanation_info['tag_length']}")
                stats['explanations_found'] += 1

                # Translate each step individually
                explanation_nl = translate_explanation_steps_individually(
                    combined_explanation_info['steps'], client, gen_params
                )

                if explanation_nl and not explanation_nl.startswith("[ERROR]"):
                    print(f"   ✅ Step-by-step explanation: {explanation_nl[:100]}...")
                else:
                    print(f"   ❌ Error translating steps")

                # Update DataFrame with explanation info
                df.at[idx, 'Explanation_NL'] = explanation_nl
                df.at[idx, 'Explanation_Found'] = True
                df.at[idx, 'Explanation_Tag_Length'] = combined_explanation_info['tag_length']
                df.at[idx, 'Explanation_Count'] = combined_explanation_info['explanation_count']
                df.at[idx, 'Explanation_Steps_Raw'] = " → ".join(combined_explanation_info['steps'])
                df.at[idx, 'Triple_Key'] = combined_explanation_info['triple_keys']
                df.at[idx, 'Answer_Count'] = combined_explanation_info['answer_count']

            else:
                print(f"   ⚠️  No explanations found for this SPARQL query")
                df.at[idx, 'Explanation_NL'] = "No explanation available"
                df.at[idx, 'Explanation_Found'] = False

            # Convert SPARQL to Natural Language Question
            sparql_to_nl_prompt = get_sparql_to_nl_prompt_template(answer_type, task_type)
            prompt = sparql_to_nl_prompt.format(language_string=language_string, sparql=sparql)

            start_time = time.time()
            messages = [{"role": "user", "content": prompt}]

            nl_question, model_version_sparql = make_api_call_with_retry(
                client, messages, gen_params, CONFIG['max_retries']
            )
            end_time = time.time()

            # Show result
            if nl_question.startswith("[ERROR]"):
                print(f"   ❌ Error generating question: {nl_question}")
                stats['errors'] += 1
            else:
                print(f"   ✅ Generated Question: {nl_question}")
                stats['processed'] += 1

            # Save response to DataFrame
            df.at[idx, "Question"] = nl_question

            # Create comprehensive log entry
            log_entry = {
                "query_index": idx,
                "sparql_query": sparql,
                "generated_question": nl_question,
                "explanation_nl": explanation_nl,
                "explanation_found": combined_explanation_info is not None,
                "explanation_tag_length": combined_explanation_info['tag_length'] if combined_explanation_info else 0,
                "explanation_count": combined_explanation_info['explanation_count'] if combined_explanation_info else 0,
                "explanation_steps_count": len(combined_explanation_info['steps']) if combined_explanation_info else 0,
                "triple_keys": combined_explanation_info['triple_keys'] if combined_explanation_info else "",
                "answer_count": combined_explanation_info['answer_count'] if combined_explanation_info else 0,
                "language": language_string,
                "answer_type": answer_type,
                "task_type": task_type,
                "model": "gpt-4o-mini",
                "model_version_sparql": model_version_sparql,
                "python_version": sys.version.split()[0],
                "timestamp_request": datetime.now().isoformat(),
                "response_time_sec": round(end_time - start_time, 3),
                "success": not nl_question.startswith("[ERROR]")
            }

            log_entry.update(gen_params)
            logs.append(log_entry)

            # Rate limiting
            time.sleep(CONFIG['api_delay'])

        except Exception as e:
            logger.error(f"Error processing row {idx}: {str(e)}")
            stats['errors'] += 1

        # Save checkpoint
        if (idx + 1) % CONFIG['checkpoint_frequency'] == 0:
            save_checkpoint(df, logs, timestamp, (idx + 1) // CONFIG['checkpoint_frequency'])

    # Save final outputs
    output_filename = output_dir / "sparql_nl_with_explanations.csv"
    log_filename = output_dir / "processing_log.csv"
    stats_filename = output_dir / "processing_stats.txt"

    df.to_csv(output_filename, index=False)
    pd.DataFrame(logs).to_csv(log_filename, index=False)

    # Save processing statistics
    total_time = time.time() - stats['start_time']
    explanation_rate = stats['explanations_found'] / stats['total'] * 100 if stats['total'] > 0 else 0

    with open(stats_filename, 'w') as f:
        f.write(f"SPARQL to NL + Multi-Answer Explanations Processing Statistics\n")
        f.write(f"=" * 60 + "\n")
        f.write(f"Total questions: {stats['total']}\n")
        f.write(f"  - Binary questions: {stats['bin_questions']}\n")
        f.write(f"  - Multi-choice questions: {stats['mc_questions']}\n")
        f.write(f"Successfully processed: {stats['processed']}\n")
        f.write(f"Errors: {stats['errors']}\n")
        f.write(f"Success rate: {stats['processed']/stats['total']*100:.1f}%\n")
        f.write(f"Explanations found: {stats['explanations_found']}\n")
        f.write(f"Explanation coverage: {explanation_rate:.1f}%\n")
        f.write(f"Total processing time: {total_time:.2f} seconds\n")
        f.write(f"Average time per question: {total_time/stats['total']:.2f} seconds\n")

    print(f"\n✅ Done! Results saved to {output_dir}/")
    print(f"📊 Success rate: {stats['processed']}/{stats['total']} ({stats['processed']/stats['total']*100:.1f}%)")
    print(f"🔍 Explanation coverage: {stats['explanations_found']}/{stats['total']} ({explanation_rate:.1f}%)")
    print(f"📈 Question breakdown: {stats['bin_questions']} Binary, {stats['mc_questions']} Multi-Choice")

    logger.info(f"Processing completed. Success rate: {stats['processed']}/{stats['total']}, Explanation coverage: {explanation_rate:.1f}%")

if __name__ == "__main__":
    main()