"""
api_calls.py - Enhanced with DeepEval integration and comprehensive metrics
"""
import os
import time
import sys
import json
from datetime import datetime
import openai
from dotenv import load_dotenv
import concurrent.futures
from threading import Lock
import pandas as pd
import hashlib
import numpy as np

# DeepEval imports
try:
    from deepeval import evaluate
    from deepeval.metrics import (
        AnswerRelevancyMetric,
        FaithfulnessMetric,
        HallucinationMetric,
        BiasMetric,
        ToxicityMetric
    )
    from deepeval.test_case import LLMTestCase
    from deepeval.dataset import EvaluationDataset
    DEEPEVAL_AVAILABLE = True
except ImportError:
    print("⚠️ DeepEval not installed. Install with: pip install deepeval")
    DEEPEVAL_AVAILABLE = False

load_dotenv()

# Enhanced model dictionary with latest models
MODELS = {
    "gpt-4o-mini": "gpt-4o-mini",
    "deepseek-reasoner": "deepseek-reasoner",
    "llama-4-maverick": "meta-llama/llama-4-maverick"
}

def get_model_params():
    """Enhanced model parameters optimized for reasoning and confidence extraction"""
    base_params = {
        "temperature": 0.0,  # Deterministic for consistency
        "max_tokens": 4096,
        "top_p": 1.0,
        "presence_penalty": 0.0,
        "frequency_penalty": 0.0,
        "logprobs": True,
        "top_logprobs": 10  # Increased for better confidence analysis
    }

    return {model: base_params.copy() for model in MODELS.keys()}

# Initialize API clients
openai_api_key = os.getenv("OPENAI_API_KEY")
deepseek_api_key = os.getenv("DEEPSEEK_API_KEY")
openrouter_api_key = os.getenv("OPENROUTER_API_KEY")

if not all([openai_api_key, deepseek_api_key, openrouter_api_key]):
    print("⚠️ Some API keys missing. Check your .env file.")

# Client initialization
openai_client = openai.OpenAI(api_key=openai_api_key) if openai_api_key else None
deepseek_client = openai.OpenAI(api_key=deepseek_api_key, base_url="https://api.deepseek.com") if deepseek_api_key else None
openrouter_client = openai.OpenAI(base_url="https://openrouter.ai/api/v1", api_key=openrouter_api_key) if openrouter_api_key else None

def calculate_enhanced_confidence_score(logprobs_data):
    """Enhanced confidence calculation with multiple metrics"""
    if not logprobs_data or not logprobs_data.content:
        return {
            'confidence_score': 0.0,
            'entropy': 0.0,
            'top_token_prob': 0.0,
            'consistency_score': 0.0
        }

    token_probs = []
    entropies = []

    for token_data in logprobs_data.content:
        if token_data.logprob is not None:
            # Convert log probability to probability
            prob = min(1.0, max(0.0, 2 ** token_data.logprob))
            token_probs.append(prob)

            # Calculate entropy for this token's distribution
            if hasattr(token_data, 'top_logprobs') and token_data.top_logprobs:
                top_probs = [2 ** lp.logprob for lp in token_data.top_logprobs]
                top_probs = [max(0.0, min(1.0, p)) for p in top_probs]  # Clamp

                # Normalize probabilities
                total_prob = sum(top_probs)
                if total_prob > 0:
                    normalized_probs = [p / total_prob for p in top_probs]
                    # Calculate entropy: -sum(p * log(p))
                    entropy = -sum(p * np.log(p + 1e-10) for p in normalized_probs if p > 0)
                    entropies.append(entropy)

    # Overall confidence metrics
    mean_confidence = np.mean(token_probs) if token_probs else 0.0
    top_token_prob = max(token_probs) if token_probs else 0.0
    mean_entropy = np.mean(entropies) if entropies else 0.0

    # Consistency score (inverse of variance)
    consistency_score = 1.0 / (1.0 + np.var(token_probs)) if len(token_probs) > 1 else 1.0

    return {
        'confidence_score': round(mean_confidence, 4),
        'entropy': round(mean_entropy, 4),
        'top_token_prob': round(top_token_prob, 4),
        'consistency_score': round(consistency_score, 4)
    }

def extract_enhanced_reasoning_chain(content, model_name):
    """Enhanced reasoning chain extraction with pattern recognition"""
    reasoning_chain = []
    reasoning_patterns = {
        'step_indicators': ['step', 'first', 'second', 'third', 'then', 'next', 'finally', 'therefore'],
        'logical_connectors': ['because', 'since', 'thus', 'hence', 'consequently', 'as a result'],
        'reasoning_phrases': ['let me think', 'considering', 'given that', 'we can conclude', 'it follows'],
        'deepseek_markers': ['<think>', '</think>', '<reasoning>', '</reasoning>'],
        'chain_of_thought': ['chain of thought', 'step by step', 'reasoning process']
    }

    lines = content.split('\n')

    # Extract DeepSeek thinking blocks
    if any(marker in content for marker in reasoning_patterns['deepseek_markers']):
        if "<think>" in content and "</think>" in content:
            start = content.find("<think>") + 7
            end = content.find("</think>")
            thinking_content = content[start:end].strip()
            reasoning_chain.extend(thinking_content.split('\n'))

    # Extract step-by-step reasoning
    for line in lines:
        line_lower = line.lower().strip()
        if any(indicator in line_lower for indicator in reasoning_patterns['step_indicators']):
            reasoning_chain.append(line.strip())
        elif any(connector in line_lower for connector in reasoning_patterns['logical_connectors']):
            reasoning_chain.append(line.strip())
        elif any(phrase in line_lower for phrase in reasoning_patterns['reasoning_phrases']):
            reasoning_chain.append(line.strip())

    # Remove duplicates while preserving order
    seen = set()
    unique_chain = []
    for item in reasoning_chain:
        if item and item not in seen and len(item) > 10:  # Filter short/empty items
            seen.add(item)
            unique_chain.append(item)

    return unique_chain

def evaluate_response_quality(response_content, expected_answer, question_type):
    """Enhanced response quality evaluation"""
    quality_metrics = {
        'correctness': 0.0,
        'completeness': 0.0,
        'clarity': 0.0,
        'logical_structure': 0.0
    }

    if not response_content:
        return quality_metrics

    response_lower = response_content.lower().strip()
    expected_lower = expected_answer.lower().strip() if expected_answer else ""

    # Correctness evaluation
    if question_type == "BIN":
        if expected_lower == "true":
            if "true" in response_lower and "false" not in response_lower:
                quality_metrics['correctness'] = 1.0
            elif "true" in response_lower and "false" in response_lower:
                quality_metrics['correctness'] = 0.5
        elif expected_lower == "false":
            if "false" in response_lower and "true" not in response_lower:
                quality_metrics['correctness'] = 1.0
            elif "false" in response_lower and "true" in response_lower:
                quality_metrics['correctness'] = 0.5
    else:
        # Multi-choice evaluation
        if expected_lower and expected_lower in response_lower:
            quality_metrics['correctness'] = 1.0
        elif expected_lower and any(word in response_lower for word in expected_lower.split()):
            quality_metrics['correctness'] = 0.5

    # Completeness (response length and detail)
    word_count = len(response_content.split())
    if word_count > 50:
        quality_metrics['completeness'] = 1.0
    elif word_count > 20:
        quality_metrics['completeness'] = 0.7
    elif word_count > 10:
        quality_metrics['completeness'] = 0.5
    else:
        quality_metrics['completeness'] = 0.3

    # Clarity (proper sentence structure)
    sentences = response_content.split('.')
    complete_sentences = [s for s in sentences if len(s.strip()) > 5]
    if len(complete_sentences) >= 2:
        quality_metrics['clarity'] = 1.0
    elif len(complete_sentences) == 1:
        quality_metrics['clarity'] = 0.7
    else:
        quality_metrics['clarity'] = 0.3

    # Logical structure (presence of reasoning indicators)
    logical_indicators = ['because', 'therefore', 'since', 'thus', 'first', 'then', 'finally']
    found_indicators = sum(1 for indicator in logical_indicators if indicator in response_lower)
    quality_metrics['logical_structure'] = min(found_indicators / 3.0, 1.0)

    return quality_metrics

def run_deepeval_analysis(test_cases, model_name):
    """Run DeepEval analysis if available"""
    if not DEEPEVAL_AVAILABLE or not test_cases:
        return {}

    try:
        # Initialize metrics with model-specific settings
        metrics = [
            AnswerRelevancyMetric(threshold=0.7, model="gpt-4o-mini", include_reason=True),
            FaithfulnessMetric(threshold=0.7, model="gpt-4o-mini", include_reason=True),
            HallucinationMetric(threshold=0.3, model="gpt-4o-mini", include_reason=True),
            BiasMetric(threshold=0.5, model="gpt-4o-mini", include_reason=True),
            ToxicityMetric(threshold=0.5, model="gpt-4o-mini", include_reason=True)
        ]

        # Create dataset
        dataset = EvaluationDataset(test_cases=test_cases[:10])  # Limit for testing

        # Run evaluation
        results = evaluate(dataset, metrics)

        # Process results
        deepeval_scores = {}
        for i, metric in enumerate(metrics):
            metric_name = metric.__class__.__name__.replace('Metric', '').lower()
            metric_results = [r for r in results if hasattr(r, 'metric_metadata')]

            if metric_results:
                scores = []
                for result in metric_results:
                    if hasattr(result, 'score'):
                        scores.append(result.score)
                    elif hasattr(result, 'success'):
                        scores.append(1.0 if result.success else 0.0)

                if scores:
                    deepeval_scores[f"deepeval_{metric_name}"] = {
                        'mean': np.mean(scores),
                        'std': np.std(scores),
                        'min': np.min(scores),
                        'max': np.max(scores)
                    }

        return deepeval_scores

    except Exception as e:
        print(f"⚠️ DeepEval analysis failed for {model_name}: {e}")
        return {}

def create_enhanced_metrics_entry(idx, row, query, question_column, display_name, model_id,
                                  response, content, start_time, end_time, attempt, model_params,
                                  ontology_context="", error=None):
    """Create comprehensive enhanced metrics entry"""
    response_time = round(end_time - start_time, 3)

    # Basic metrics
    metrics = {
        # Query identification
        "query_index": idx,
        "query_hash": hashlib.md5(query.encode()).hexdigest()[:8],
        "query_text": query,
        "question_column_used": question_column,
        "ontology_name": row.get("Root Entity", "Unknown"),
        "answer_type": row.get("Answer Type", "Unknown"),
        "expected_answer": row.get("Answer", "Unknown"),
        "complexity_tags": row.get("tags", ""),

        # Model information
        "model_display_name": display_name,
        "model_api_id": model_id,
        "attempt_number": attempt + 1,
        "timestamp_request": datetime.now().isoformat(),

        # Performance metrics
        "response_time_seconds": response_time,
        "response_length_chars": len(content) if content else 0,
        "response_length_words": len(content.split()) if content else 0,

        # Response content
        "full_response": content,
        "response_preview": content[:200] if content else "",

        # Technical details
        "python_version": sys.version.split()[0],
        "error_occurred": error is not None,
        "error_message": str(error) if error else None
    }

    # Add model parameters
    if display_name in model_params:
        for param, value in model_params[display_name].items():
            metrics[f"param_{param}"] = value

    # Enhanced metrics for successful responses
    if response and not error and content:
        # Enhanced confidence metrics
        if hasattr(response, 'choices') and response.choices:
            choice = response.choices[0]
            if hasattr(choice, 'logprobs') and choice.logprobs:
                confidence_metrics = calculate_enhanced_confidence_score(choice.logprobs)
                metrics.update(confidence_metrics)

                metrics.update({
                    "has_logprobs": True,
                    "finish_reason": choice.finish_reason if hasattr(choice, 'finish_reason') else "Unknown"
                })
            else:
                metrics.update({
                    "confidence_score": 0.0,
                    "entropy": 0.0,
                    "top_token_prob": 0.0,
                    "consistency_score": 0.0,
                    "has_logprobs": False
                })

        # Enhanced reasoning analysis
        reasoning_chain = extract_enhanced_reasoning_chain(content, display_name)
        metrics.update({
            "reasoning_chain": json.dumps(reasoning_chain),
            "reasoning_steps_count": len(reasoning_chain),
            "has_explicit_reasoning": len(reasoning_chain) > 0,
            "reasoning_depth": "deep" if len(reasoning_chain) > 3 else "shallow" if reasoning_chain else "none"
        })

        # Response quality evaluation
        expected_answer = row.get("Answer", "Unknown")
        answer_type = row.get("Answer Type", "BIN")
        quality_metrics = evaluate_response_quality(content, expected_answer, answer_type)

        for quality_metric, score in quality_metrics.items():
            metrics[f"quality_{quality_metric}"] = round(score, 3)

        # Token usage information
        if hasattr(response, 'usage'):
            metrics.update({
                "prompt_tokens": response.usage.prompt_tokens if hasattr(response.usage, 'prompt_tokens') else 0,
                "completion_tokens": response.usage.completion_tokens if hasattr(response.usage, 'completion_tokens') else 0,
                "total_tokens": response.usage.total_tokens if hasattr(response.usage, 'total_tokens') else 0,
                "tokens_per_second": round((response.usage.completion_tokens or 0) / max(response_time, 0.001), 2)
            })

        # Model-specific information
        if hasattr(response, 'model'):
            metrics["actual_model_version"] = response.model
        if hasattr(response, 'system_fingerprint'):
            metrics["system_fingerprint"] = response.system_fingerprint
        if hasattr(response, 'id'):
            metrics["response_id"] = response.id

    return metrics

def load_ontology_context(ontology_base_path, ontology_name, context_mode):
    """Load ontology context based on mode (TTL or JSON)"""
    try:
        if context_mode == "ttl":
            # ontology_name should be the TTL filename without extension
            ontology_path = os.path.join(ontology_base_path, f"{ontology_name}.ttl")

            if not os.path.exists(ontology_path):
                # Try alternative extensions if TTL not found
                for ext in ['.owl', '.rdf', '.n3']:
                    alt_path = os.path.join(ontology_base_path, f"{ontology_name}{ext}")
                    if os.path.exists(alt_path):
                        ontology_path = alt_path
                        break
                else:
                    return f"[ERROR: No ontology file found for {ontology_name} in {ontology_base_path}]"

            with open(ontology_path, 'r', encoding='utf-8') as f:
                content = f.read()
                print(f"✅ Loaded TTL context from: {ontology_path} ({len(content)} chars)")
                return content

        elif context_mode == "json":
            ontology_path = os.path.join(ontology_base_path, f"{ontology_name}.json")
            with open(ontology_path, 'r', encoding='utf-8') as f:
                return json.dumps(json.load(f), indent=2)
        else:
            return f"[ERROR: Unknown context mode: {context_mode}]"

    except FileNotFoundError:
        return f"[ERROR: Ontology file not found: {ontology_name} in {ontology_base_path}]"
    except Exception as e:
        return f"[ERROR: Failed to load ontology {ontology_name}: {str(e)}]"

def create_context_specific_prompt(query, ontology_context, context_mode, answer_type):
    """Create prompts specific to the experimental context"""

    if context_mode == "ttl":
        # SPARQL + TTL context
        base_prompt = f"""You are an expert in SPARQL and OWL ontologies. Given the following TTL ontology context and SPARQL query, provide a precise answer based on logical inference.

Ontology Context (TTL):
{ontology_context}

SPARQL Query:
{query}

Instructions:
- Analyze the ontology structure carefully
- Execute the SPARQL query logically
- Provide a clear, precise answer
- Show your reasoning process

Answer:"""

    elif context_mode == "json" and "explanation_context" in str(ontology_context):
        # NL + Explanation context
        base_prompt = f"""You are an expert in ontological reasoning. Use the provided reasoning explanation to guide your thinking, then answer the question.

Question: {query}

Reasoning Guidance:
{ontology_context}

Instructions:
- Study the reasoning explanation to understand the inference pattern
- Apply similar reasoning to the given question
- Show your reasoning process step by step
- Provide a clear, confident answer

Answer:"""

    else:
        # NL + Verbalized context (default JSON)
        if answer_type == "BIN":
            instruction = "Answer with TRUE or FALSE and explain your reasoning."
        elif answer_type == "MC":
            instruction = "Provide the correct answer(s) and explain your reasoning."
        else:
            instruction = "Provide a clear answer and explain your reasoning."

        base_prompt = f"""Answer the following question based on the provided ontological relationships. Show your reasoning step by step.

Question: {query}

Ontology Context:
{ontology_context}

Instructions:
- Analyze the relationships and facts provided
- Use logical reasoning to derive the answer
- {instruction}

Answer:"""

    return base_prompt

def run_llm_reasoning(df, ontology_base_path, models=None, model_params=None, log_each=False,
                      context_mode="ttl", show_qa=True, max_workers=3, batch_size=10,
                      question_column="Question", save_detailed_metrics=True,
                      enable_deepeval=False):
    """
    Enhanced LLM reasoning with comprehensive metrics and DeepEval integration
    """
    if models is None:
        models = MODELS

    df = clean_input_dataframe(df)

    if question_column not in df.columns:
        available_cols = [col for col in df.columns if col in ["Question", "SPARQL Query"]]
        if available_cols:
            question_column = available_cols[0]
            print(f"Using '{question_column}' column for questions")
        else:
            raise ValueError(f"DataFrame must contain '{question_column}' column")

    if model_params is None:
        model_params = get_model_params()

    # Initialize response columns with enhanced metrics
    for display_name in models:
        df[f"{display_name}_response"] = ""
        if save_detailed_metrics:
            df[f"{display_name}_confidence"] = 0.0
            df[f"{display_name}_entropy"] = 0.0
            df[f"{display_name}_consistency"] = 0.0
            df[f"{display_name}_response_time"] = 0.0
            df[f"{display_name}_token_count"] = 0
            df[f"{display_name}_reasoning_steps"] = 0
            df[f"{display_name}_quality_correctness"] = 0.0
            df[f"{display_name}_quality_completeness"] = 0.0
            df[f"{display_name}_quality_clarity"] = 0.0
            df[f"{display_name}_quality_logical_structure"] = 0.0

    total_questions = len(df)
    print(f"🚀 Processing {total_questions} questions with {len(models)} models...")
    print(f"Enhanced metrics: {'ENABLED' if save_detailed_metrics else 'DISABLED'}")
    print(f"DeepEval integration: {'ENABLED' if enable_deepeval and DEEPEVAL_AVAILABLE else 'DISABLED'}")
    print("=" * 80)

    logs = []
    detailed_metrics = []
    deepeval_test_cases = {model: [] for model in models}
    lock = Lock()
    completed = 0
    failed_queries = []

    def process_single_query(args):
        nonlocal completed, logs, failed_queries, detailed_metrics, deepeval_test_cases

        idx, row = args
        query = row[question_column]
        ontology_name = row.get("Root Entity", "Unknown")
        answer_type = row.get("Answer Type", "BIN")

        # Load ontology context
        if hasattr(row, 'explanation_context') and row.explanation_context:
            # Use explanation context if available
            ontology_context = row.explanation_context
        else:
            # Load standard ontology context
            ontology_context = load_ontology_context(ontology_base_path, ontology_name, context_mode)

        query_results = {}
        query_failed = False

        for display_name, model_id in models.items():
            max_retries = 2
            content = None

            for attempt in range(max_retries):
                try:
                    start_time = time.time()

                    # Create context-specific prompt
                    full_prompt = create_context_specific_prompt(query, ontology_context, context_mode, answer_type)

                    messages = [{"role": "user", "content": full_prompt}]
                    gen_params = model_params[display_name].copy()

                    # Select appropriate client
                    client = None
                    if display_name == "gpt-4o-mini" and openai_client:
                        client = openai_client
                    elif display_name == "deepseek-reasoner" and deepseek_client:
                        client = deepseek_client
                    elif display_name in ["llama-4-maverick"] and openrouter_client:
                        client = openrouter_client

                    if not client:
                        raise ValueError(f"No client available for model: {display_name}")

                    response = client.chat.completions.create(
                        model=model_id,
                        messages=messages,
                        **gen_params
                    )

                    end_time = time.time()
                    content = response.choices[0].message.content
                    query_results[display_name] = content

                    # Create enhanced metrics entry
                    metrics_entry = create_enhanced_metrics_entry(
                        idx, row, query, question_column, display_name, model_id,
                        response, content, start_time, end_time, attempt, model_params,
                        ontology_context
                    )

                    with lock:
                        detailed_metrics.append(metrics_entry)

                        # Update DataFrame with enhanced metrics
                        if save_detailed_metrics:
                            df.at[idx, f"{display_name}_confidence"] = metrics_entry.get("confidence_score", 0.0)
                            df.at[idx, f"{display_name}_entropy"] = metrics_entry.get("entropy", 0.0)
                            df.at[idx, f"{display_name}_consistency"] = metrics_entry.get("consistency_score", 0.0)
                            df.at[idx, f"{display_name}_response_time"] = metrics_entry["response_time_seconds"]
                            df.at[idx, f"{display_name}_token_count"] = metrics_entry.get("total_tokens", 0)
                            df.at[idx, f"{display_name}_reasoning_steps"] = metrics_entry.get("reasoning_steps_count", 0)
                            df.at[idx, f"{display_name}_quality_correctness"] = metrics_entry.get("quality_correctness", 0.0)
                            df.at[idx, f"{display_name}_quality_completeness"] = metrics_entry.get("quality_completeness", 0.0)
                            df.at[idx, f"{display_name}_quality_clarity"] = metrics_entry.get("quality_clarity", 0.0)
                            df.at[idx, f"{display_name}_quality_logical_structure"] = metrics_entry.get("quality_logical_structure", 0.0)

                        # Prepare DeepEval test case
                        if enable_deepeval and DEEPEVAL_AVAILABLE:
                            test_case = LLMTestCase(
                                input=query,
                                actual_output=content,
                                expected_output=row.get("Answer", "TRUE"),
                                context=[ontology_context],
                                retrieval_context=[ontology_context]
                            )
                            deepeval_test_cases[display_name].append(test_case)

                        # Enhanced log entry
                        log_entry = {
                            "Query_index": idx,
                            "Query": query,
                            "model": display_name,
                            "timestamp_request": metrics_entry["timestamp_request"],
                            "response_time_sec": metrics_entry["response_time_seconds"],
                            "confidence_score": metrics_entry.get("confidence_score", 0.0),
                            "entropy": metrics_entry.get("entropy", 0.0),
                            "reasoning_steps": metrics_entry.get("reasoning_steps_count", 0),
                            "quality_correctness": metrics_entry.get("quality_correctness", 0.0),
                            "ontology_name": ontology_name,
                            "complexity_tags": row.get("tags", "")
                        }
                        logs.append(log_entry)

                    break  # Success

                except Exception as e:
                    end_time = time.time()
                    if attempt == max_retries - 1:  # Last attempt
                        error_msg = f"[ERROR] {type(e).__name__}: {str(e)}"
                        content = error_msg
                        query_results[display_name] = content
                        query_failed = True

                        # Create error metrics entry
                        error_metrics = create_enhanced_metrics_entry(
                            idx, row, query, question_column, display_name, model_id,
                            None, content, start_time, end_time, attempt, model_params,
                            ontology_context, error=e
                        )

                        with lock:
                            detailed_metrics.append(error_metrics)
                            if show_qa:
                                print(f"❌ Q{idx+1} - {display_name}: {error_msg}")
                    else:
                        time.sleep(1)

        # Update DataFrame
        for display_name in models:
            df.at[idx, f"{display_name}_response"] = query_results.get(display_name, "[ERROR] No response")

        # Track progress
        with lock:
            completed += 1
            if query_failed:
                failed_queries.append(idx)

            if show_qa:
                print(f"\n✅ Q{idx+1}/{total_questions}: {query}")
                print(f"   Entity: {ontology_name} | Type: {answer_type}")
                for display_name in models:
                    result = query_results.get(display_name, "No response")
                    if save_detailed_metrics:
                        confidence = df.at[idx, f"{display_name}_confidence"]
                        quality = df.at[idx, f"{display_name}_quality_correctness"]
                        reasoning_steps = df.at[idx, f"{display_name}_reasoning_steps"]
                        print(f"   🤖 {display_name}: {result[:100]}...")
                        print(f"      📊 Confidence: {confidence:.3f} | Quality: {quality:.3f} | Steps: {reasoning_steps}")
                    else:
                        print(f"   🤖 {display_name}: {result}")
                print("-" * 80)
            elif completed % 10 == 0 or completed == total_questions:
                success_rate = ((completed - len(failed_queries)) / completed) * 100
                print(f"Progress: {completed}/{total_questions} ({success_rate:.1f}% success rate)")

        return idx, query_results

    # Process in parallel
    print(f"Starting parallel processing with {max_workers} workers...")
    start_total = time.time()

    query_args = [(idx, row) for idx, row in df.iterrows()]

    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = [executor.submit(process_single_query, args) for args in query_args]

        for future in concurrent.futures.as_completed(futures):
            try:
                future.result()
            except Exception as e:
                print(f"Parallel processing error: {e}")

    end_total = time.time()
    total_time = end_total - start_total

    # Run DeepEval analysis if enabled
    deepeval_results = {}
    if enable_deepeval and DEEPEVAL_AVAILABLE:
        print("\n🎯 Running DeepEval analysis...")
        for model_name, test_cases in deepeval_test_cases.items():
            if test_cases:
                print(f"  Analyzing {model_name} ({len(test_cases)} test cases)...")
                deepeval_results[model_name] = run_deepeval_analysis(test_cases, model_name)

    print("=" * 80)
    print(f"✅ ENHANCED PROCESSING COMPLETED!")
    print(f"Total time: {total_time:.1f}s ({total_time/60:.1f} minutes)")
    print(f"Average time per query: {total_time/total_questions:.2f}s")
    print(f"Successful queries: {completed - len(failed_queries)}/{completed}")
    print(f"Enhanced metrics captured: {len(detailed_metrics)} entries")
    if deepeval_results:
        print(f"DeepEval analysis completed for {len(deepeval_results)} models")

    return df, logs, detailed_metrics, deepeval_results

def clean_input_dataframe(df):
    """Enhanced DataFrame cleaning"""
    columns_to_drop = [
        "Task ID temp",
        "Bin_Size of ontology ABox",
        "Bin_Avg Min Explanation Size",
        "strata",
        "split"
    ]

    existing_columns_to_drop = [col for col in columns_to_drop if col in df.columns]
    if existing_columns_to_drop:
        df = df.drop(columns=existing_columns_to_drop)
        print(f"Dropped columns: {existing_columns_to_drop}")

    return df

def resume_failed_queries(df, failed_indices, ontology_base_path, models=None, model_params=None,
                          context_mode="ttl", question_column="Question", enable_deepeval=False):
    """Enhanced resume processing with comprehensive metrics"""
    if not failed_indices:
        print("No failed queries to resume.")
        return df, [], [], {}

    if models is None:
        models = MODELS

    print(f"Resuming {len(failed_indices)} failed queries with enhanced metrics...")
    failed_df = df.iloc[failed_indices].copy()
    failed_df = failed_df.reset_index(drop=True)

    result_df, logs, detailed_metrics, deepeval_results = run_llm_reasoning(
        failed_df,
        ontology_base_path,
        models=models,
        model_params=model_params,
        context_mode=context_mode,
        show_qa=True,
        max_workers=2,
        question_column=question_column,
        save_detailed_metrics=True,
        enable_deepeval=enable_deepeval
    )

    # Update original DataFrame
    enhanced_columns = [
        '_response', '_confidence', '_entropy', '_consistency', '_response_time',
        '_token_count', '_reasoning_steps', '_quality_correctness', '_quality_completeness',
        '_quality_clarity', '_quality_logical_structure'
    ]

    for i, failed_idx in enumerate(failed_indices):
        for model in models:
            for suffix in enhanced_columns:
                col_name = f"{model}{suffix}"
                if col_name in result_df.columns:
                    df.at[failed_idx, col_name] = result_df.iloc[i][col_name]

    return df, logs, detailed_metrics, deepeval_results

def calculate_model_performance_summary(df, models):
    """Calculate comprehensive performance summary for all models"""
    summary = {}

    for model in models:
        model_summary = {
            'response_metrics': {},
            'quality_metrics': {},
            'confidence_metrics': {},
            'performance_metrics': {}
        }

        # Response metrics
        response_col = f"{model}_response"
        if response_col in df.columns:
            responses = df[response_col]
            error_responses = responses.str.startswith('[ERROR]').sum()
            valid_responses = len(responses) - error_responses

            model_summary['response_metrics'] = {
                'total_responses': len(responses),
                'valid_responses': valid_responses,
                'error_responses': error_responses,
                'success_rate': valid_responses / len(responses) if len(responses) > 0 else 0
            }

        # Quality metrics
        quality_cols = [f"{model}_quality_correctness", f"{model}_quality_completeness",
                        f"{model}_quality_clarity", f"{model}_quality_logical_structure"]
        for col in quality_cols:
            if col in df.columns:
                metric_name = col.split('_quality_')[1]
                model_summary['quality_metrics'][metric_name] = {
                    'mean': df[col].mean(),
                    'std': df[col].std(),
                    'min': df[col].min(),
                    'max': df[col].max()
                }

        # Confidence metrics
        conf_cols = [f"{model}_confidence", f"{model}_entropy", f"{model}_consistency"]
        for col in conf_cols:
            if col in df.columns:
                metric_name = col.split(f'{model}_')[1]
                model_summary['confidence_metrics'][metric_name] = {
                    'mean': df[col].mean(),
                    'std': df[col].std(),
                    'min': df[col].min(),
                    'max': df[col].max()
                }

        # Performance metrics
        perf_cols = [f"{model}_response_time", f"{model}_token_count", f"{model}_reasoning_steps"]
        for col in perf_cols:
            if col in df.columns:
                metric_name = col.split(f'{model}_')[1]
                model_summary['performance_metrics'][metric_name] = {
                    'mean': df[col].mean(),
                    'std': df[col].std(),
                    'min': df[col].min(),
                    'max': df[col].max()
                }

        summary[model] = model_summary

    return summary

def run_comprehensive_deepeval_analysis(all_test_cases, models):
    """Run DeepEval analysis for all models comprehensively"""
    if not DEEPEVAL_AVAILABLE:
        return {}

    comprehensive_results = {}

    for model_name, test_cases in all_test_cases.items():
        if model_name not in models or not test_cases:
            continue

        print(f"🎯 Running comprehensive DeepEval for {model_name}...")
        print(f"   Test cases: {len(test_cases)}")

        try:
            # Sample test cases if too many
            from test_config import DEEPEVAL_CONFIG
            sample_size = DEEPEVAL_CONFIG.get('sample_size_per_model', 50)
            if len(test_cases) > sample_size:
                test_cases_sample = test_cases[:sample_size]
                print(f"   Sampling {sample_size} test cases for efficiency")
            else:
                test_cases_sample = test_cases

            # Run analysis
            model_results = run_deepeval_analysis(test_cases_sample, model_name)
            comprehensive_results[model_name] = model_results

            print(f"   ✅ Completed DeepEval for {model_name}")

        except Exception as e:
            print(f"   ❌ DeepEval failed for {model_name}: {e}")
            comprehensive_results[model_name] = {'error': str(e)}

    return comprehensive_results

def validate_experiment_inputs(df, ontology_base_path, models, question_column):
    """Validate inputs for LLM experiments"""
    errors = []

    # Check DataFrame
    if df.empty:
        errors.append("DataFrame is empty")

    if question_column not in df.columns:
        errors.append(f"Question column '{question_column}' not found in DataFrame")

    # Check required columns
    required_cols = ["Root Entity", "Answer Type"]
    missing_cols = [col for col in required_cols if col not in df.columns]
    if missing_cols:
        errors.append(f"Missing required columns: {missing_cols}")

    # Check ontology path
    if not os.path.exists(ontology_base_path):
        errors.append(f"Ontology base path does not exist: {ontology_base_path}")

    # Check models
    if not models:
        errors.append("No models specified")

    # Check API keys
    missing_keys = []
    if "gpt-4o-mini" in models and not openai_api_key:
        missing_keys.append("OPENAI_API_KEY")
    if "deepseek-reasoner" in models and not deepseek_api_key:
        missing_keys.append("DEEPSEEK_API_KEY")
    if "llama-4-maverick" in models and not openrouter_api_key:
        missing_keys.append("OPENROUTER_API_KEY")

    if missing_keys:
        errors.append(f"Missing API keys: {missing_keys}")

    if errors:
        raise ValueError("Validation failed:\n" + "\n".join(f"- {error}" for error in errors))

    return True

def create_experiment_summary(config, results_df, logs, detailed_metrics, deepeval_results,
                              experiment_time, patterns=None):
    """Create standardized experiment summary"""

    total_questions = len(results_df)
    total_responses = 0
    successful_responses = 0

    # Count responses
    for model in config['models_used']:
        response_col = f"{model}_response"
        if response_col in results_df.columns:
            responses = results_df[response_col]
            total_responses += len(responses)
            successful_responses += len(responses) - responses.str.startswith('[ERROR]').sum()

    success_rate = (successful_responses / total_responses * 100) if total_responses > 0 else 0

    summary = {
        'experiment_info': {
            'type': config['experiment_type'],
            'description': config['description'],
            'timestamp': datetime.now().isoformat(),
            'test_mode': config.get('test_mode', False)
        },
        'dataset_info': {
            'total_questions': total_questions,
            'context_mode': config['context_mode'],
            'question_column': config['question_column']
        },
        'model_info': {
            'models_used': list(config['models_used'].keys()),
            'models_count': len(config['models_used'])
        },
        'performance_metrics': {
            'total_responses': total_responses,
            'successful_responses': successful_responses,
            'success_rate_percent': round(success_rate, 2),
            'experiment_time_seconds': experiment_time,
            'average_time_per_question': experiment_time / total_questions if total_questions > 0 else 0
        },
        'analysis_results': {
            'logs_count': len(logs),
            'detailed_metrics_count': len(detailed_metrics),
            'deepeval_enabled': bool(deepeval_results),
            'deepeval_models_analyzed': list(deepeval_results.keys()) if deepeval_results else []
        }
    }

    # Add pattern analysis if provided
    if patterns:
        summary['pattern_analysis'] = patterns

    # Add DeepEval results summary
    if deepeval_results:
        deepeval_summary = {}
        for model, results in deepeval_results.items():
            if isinstance(results, dict) and 'error' not in results:
                deepeval_summary[model] = {
                    'metrics_analyzed': list(results.keys()),
                    'avg_scores': {
                        metric: round(scores.get('mean', 0), 3)
                        for metric, scores in results.items()
                        if isinstance(scores, dict) and 'mean' in scores
                    }
                }
        summary['deepeval_summary'] = deepeval_summary

    return summary

# Export key functions for use in experiment scripts
__all__ = [
    'run_llm_reasoning',
    'resume_failed_queries',
    'calculate_model_performance_summary',
    'run_comprehensive_deepeval_analysis',
    'validate_experiment_inputs',
    'create_experiment_summary',
    'MODELS',
    'get_model_params'
]