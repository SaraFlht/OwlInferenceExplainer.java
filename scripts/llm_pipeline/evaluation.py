"""
evaluation.py
-------------
Streamlined automated evaluation of LLM responses with a focus on accuracy,
complexity correlation, and error analysis, generating separate reports.
"""
import pandas as pd
import numpy as np
import ast
import matplotlib.pyplot as plt
import seaborn as sns
import json
from pathlib import Path
import os
from scipy.stats import pearsonr

# Navigate to project root (2 levels up from current script)
script_dir = Path(__file__).resolve().parent  # scripts/llm_pipeline/
project_root = script_dir.parent.parent       # owl-inference-explainer/
os.chdir(project_root)

print(f"Working directory set to: {os.getcwd()}")

# --- Utility Functions ---
def parse_response_to_list(response_str):
    if pd.isna(response_str): return []
    response_str = str(response_str).strip()
    if not response_str: return []

    try:
        parsed = ast.literal_eval(response_str)
        items = parsed if isinstance(parsed, list) else [parsed]
    except (ValueError, SyntaxError):
        if response_str.upper() in ['TRUE', 'FALSE']:
            return [response_str.lower()]
        items = [item.strip() for item in response_str.split(',') if item.strip()]

    # Normalize each item: remove all whitespace and convert to lowercase
    normalized_items = []
    for item in items:
        # Remove all whitespace (spaces, tabs, newlines) and convert to lowercase
        normalized = ''.join(str(item).split()).lower()
        if normalized:  # Only add non-empty items
            normalized_items.append(normalized)

    return normalized_items

# --- Core Evaluation Logic ---
def exact_match_evaluation(answer_str, response_str):
    gold = set(parse_response_to_list(answer_str))
    pred = set(parse_response_to_list(response_str))

    missing = gold - pred  # What the model missed
    extra = pred - gold    # What the model added incorrectly
    correct = gold & pred  # What the model got right

    return {
        'n_correct': len(correct),
        'n_gold': len(gold),
        'n_pred': len(pred),
        'missing': list(missing),
        'extra': list(extra),
        'correct': list(correct)
    }

def compute_metrics(match_result):
    n_correct, n_gold, n_pred = match_result['n_correct'], match_result['n_gold'], match_result['n_pred']
    precision = n_correct / n_pred if n_pred > 0 else 0.0
    recall = n_correct / n_gold if n_gold > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    union_size = n_gold + n_pred - n_correct
    accuracy = n_correct / union_size if union_size > 0 else 1.0

    # Return both metrics and detailed results
    return {
        'precision': precision,
        'recall': recall,
        'f1': f1,
        'accuracy': accuracy,
        'missing': match_result['missing'],
        'extra': match_result['extra'],
        'correct': match_result['correct']
    }

# --- Tag Complexity Functions ---
def add_tag_complexity_to_df(df, explanation_json_path, query_col="SPARQL Query"):
    with open(explanation_json_path, 'r') as f: data = json.load(f)
    sparql_to_complexity = {}
    for entity_data in data.values():
        for triple_data in entity_data.values():
            all_raw_lengths = [len(line[4:].strip()) for exp in triple_data.get("explanations", []) for line in exp if line.startswith("TAG:")]
            valid_tag_lengths = [length for length in all_raw_lengths if length > 0]
            if valid_tag_lengths:
                complexity = {'shortest': min(valid_tag_lengths), 'longest': max(valid_tag_lengths)}
                for sparql_query in triple_data.get("sparqlQueries", []): sparql_to_complexity[sparql_query] = complexity
    df['shortest_tag_complexity'] = df[query_col].map(lambda q: sparql_to_complexity.get(q, {}).get('shortest', 0)).astype(int)
    df['longest_tag_complexity'] = df[query_col].map(lambda q: sparql_to_complexity.get(q, {}).get('longest', 0)).astype(int)
    print(f"Tag complexity added. Found data for {sum(df['longest_tag_complexity'] > 0)} out of {len(df)} queries.")
    return df

# --- Evaluation Function ---
def evaluate_model_responses(df, model_cols, answer_col="Answer"):
    for model in model_cols:
        print(f"   - Evaluating {model}...")

        # Get detailed evaluation results
        detailed_results = df.apply(
            lambda row: compute_metrics(exact_match_evaluation(row[answer_col], row[model])),
            axis=1
        )

        # Extract metrics
        metrics_df = pd.DataFrame([
            {
                'precision': result['precision'],
                'recall': result['recall'],
                'f1': result['f1'],
                'accuracy': result['accuracy']
            } for result in detailed_results
        ], index=df.index)

        # Extract detailed answer analysis
        analysis_df = pd.DataFrame([
            {
                'missing': ', '.join(result['missing']) if result['missing'] else '',
                'extra': ', '.join(result['extra']) if result['extra'] else '',
                'correct': ', '.join(result['correct']) if result['correct'] else ''
            } for result in detailed_results
        ], index=df.index)

        # Add metrics columns
        df[f"{model}_precision"] = metrics_df['precision']
        df[f"{model}_recall"] = metrics_df['recall']
        df[f"{model}_f1"] = metrics_df['f1']
        df[f"{model}_accuracy"] = metrics_df['accuracy']

        # Add detailed analysis columns
        df[f"{model}_missing"] = analysis_df['missing']
        df[f"{model}_extra"] = analysis_df['extra']
        df[f"{model}_correct"] = analysis_df['correct']

    return df

# --- Visualization Functions ---

def plot_overall_scores(df, model_cols, out_prefix):
    """Report 1: Overall mean scores and match type distribution."""
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle('Overall Performance and Match Types', fontsize=16, weight='bold')

    # Plot 1: Overall Scores (Acc, F1, P, R)
    overall_metrics = []
    for model in model_cols:
        model_name = model.replace('_response', '')
        overall_metrics.extend([
            {'Model': model_name, 'Metric': 'Accuracy', 'Score': df[f"{model}_accuracy"].mean()},
        ])

    # Create the bar plot
    bars = sns.barplot(data=pd.DataFrame(overall_metrics), x='Model', y='Score', hue='Model', ax=axes[0])
    axes[0].set_title('Overall Accuracy Scores')
    axes[0].tick_params(axis='x', rotation=15)
    axes[0].set_ylim(0, 1)

    # Add percentage labels in white inside the bars
    for i, bar in enumerate(bars.patches):
        height = bar.get_height()
        if height > 0.02:  # Only show if bar is tall enough
            axes[0].text(bar.get_x() + bar.get_width()/2., height/2,
                         f'{height:.1%}',  # Format as percentage with 1 decimal place
                         ha='center', va='center', color='white', fontweight='bold', fontsize=10)

    # Plot 2: Match Type Distribution - Only percentages
    match_data = []

    for model in model_cols:
        model_name = model.replace('_response', '')
        accuracies = df[f"{model}_accuracy"]
        total_queries = len(accuracies)

        perfect_count = (accuracies == 1.0).sum()
        partial_count = ((accuracies > 0.0) & (accuracies < 1.0)).sum()
        zero_count = (accuracies == 0.0).sum()

        match_data.extend([
            {'Model': model_name, 'Match Type': 'Perfect (Acc=1)', 'Count': perfect_count, 'Percentage': perfect_count/total_queries*100},
            {'Model': model_name, 'Match Type': 'Partial (0<Acc<1)', 'Count': partial_count, 'Percentage': partial_count/total_queries*100},
            {'Model': model_name, 'Match Type': 'Zero (Acc=0)', 'Count': zero_count, 'Percentage': zero_count/total_queries*100}
        ])

    match_df = pd.DataFrame(match_data)
    bars2 = sns.barplot(data=match_df, x='Model', y='Count', hue='Match Type', ax=axes[1])
    axes[1].set_title('Match Type Distribution')
    axes[1].tick_params(axis='x', rotation=15)

    # Add only percentage labels in white inside the bars
    for bar in bars2.patches:
        height = bar.get_height()
        if height > 10:  # Only show labels for bars tall enough to read text
            # Find the matching percentage for this bar
            for _, row in match_df.iterrows():
                expected_height = row['Count']
                # Check if this bar matches the expected height (with small tolerance)
                if abs(height - expected_height) < 0.1:
                    percentage = row['Percentage']

                    axes[1].text(bar.get_x() + bar.get_width()/2., height/2,
                                 f'{percentage:.1f}%',  # Only show percentage
                                 ha='center', va='center', color='white', fontweight='bold', fontsize=10)
                    break

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_overall_scores.png", dpi=300)
    plt.close()

def plot_type_analysis(df, model_cols, out_prefix):
    """Report 2: Accuracy breakdown by Task and Answer Type."""
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle('Performance by Task and Answer Type', fontsize=16, weight='bold')

    # Plot 1: Accuracy by Task Type
    if 'Task Type' in df.columns:
        task_perf_df = df.melt(id_vars=['Task Type'], value_vars=[f"{m}_accuracy" for m in model_cols],
                               var_name='Model', value_name='Accuracy')
        task_perf_df['Model'] = task_perf_df['Model'].str.replace('_accuracy', '').str.replace('_response', '')

        bars1 = sns.barplot(data=task_perf_df, x='Task Type', y='Accuracy', hue='Model', ax=axes[0])
        axes[0].set_title('Accuracy by Task Type')
        axes[0].set_ylim(0, 1)

        # Add percentage labels in white inside the bars
        for bar in bars1.patches:
            height = bar.get_height()
            if height > 0.02:  # Only show labels for bars tall enough
                axes[0].text(bar.get_x() + bar.get_width()/2., height/2,
                             f'{height:.1%}',
                             ha='center', va='center', color='white', fontweight='bold', fontsize=9)

    # Plot 2: Accuracy by Answer Type
    if 'Answer Type' in df.columns:
        ans_type_perf_df = df.melt(id_vars=['Answer Type'], value_vars=[f"{m}_accuracy" for m in model_cols],
                                   var_name='Model', value_name='Accuracy')
        ans_type_perf_df['Model'] = ans_type_perf_df['Model'].str.replace('_accuracy', '').str.replace('_response', '')

        bars2 = sns.barplot(data=ans_type_perf_df, x='Answer Type', y='Accuracy', hue='Model', ax=axes[1])
        axes[1].set_title('Accuracy by Answer Type')
        axes[1].set_ylim(0, 1)

        # Add percentage labels in white inside the bars
        for bar in bars2.patches:
            height = bar.get_height()
            if height > 0.02:  # Only show labels for bars tall enough
                axes[1].text(bar.get_x() + bar.get_width()/2., height/2,
                             f'{height:.1%}',
                             ha='center', va='center', color='white', fontweight='bold', fontsize=9)

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_type_analysis.png", dpi=300)
    plt.close()

def plot_error_rate_analysis(df, model_cols, out_prefix):
    """Report 3: Error rate heatmaps for shortest and longest tag complexity with 3 degrees."""
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    fig.suptitle('Error Rate vs. Tag Complexity', fontsize=16, weight='bold')

    def _plot_heatmap(complexity_col, ax):
        df_nonzero = df[df[complexity_col] > 0].copy()
        if df_nonzero.empty:
            ax.text(0.5, 0.5, 'No Data', ha='center', va='center')
            return

        # Get unique values and check if we have enough for 3 bins
        unique_values = df_nonzero[complexity_col].unique()
        n_unique = len(unique_values)

        if n_unique < 3:
            # If we don't have enough unique values for 3 bins, use what we have
            sorted_values = np.sort(unique_values)
            if n_unique == 1:
                bin_labels = [f'All\n({int(sorted_values[0])})']
                df_nonzero['Complexity Bin'] = bin_labels[0]
            else:  # n_unique == 2
                bin_labels = [f'Low\n({int(sorted_values[0])})', f'High\n({int(sorted_values[1])})']
                df_nonzero['Complexity Bin'] = df_nonzero[complexity_col].map({
                    sorted_values[0]: bin_labels[0],
                    sorted_values[1]: bin_labels[1]
                })
        else:
            # Try to create 3 equal-sized bins
            try:
                # Use qcut for equal-sized groups, but handle duplicates
                df_nonzero['Complexity Bin'] = pd.qcut(
                    df_nonzero[complexity_col].rank(method='first'),
                    q=3,
                    labels=['Low', 'Mid', 'High'],
                    duplicates='drop'
                )

                # Get the actual ranges for labels - ADD observed=True HERE
                bin_ranges = df_nonzero.groupby('Complexity Bin', observed=True)[complexity_col].agg(['min', 'max'])
                bin_labels = [f'{idx}\n({int(row["min"])}-{int(row["max"])})'
                              for idx, row in bin_ranges.iterrows()]

                # Map to descriptive labels
                label_mapping = dict(zip(['Low', 'Mid', 'High'], bin_labels))
                df_nonzero['Complexity Bin'] = df_nonzero['Complexity Bin'].map(label_mapping)

            except ValueError:
                # If qcut still fails, fall back to manual binning
                percentiles = np.percentile(df_nonzero[complexity_col], [0, 33.33, 66.67, 100])

                # Make sure bin edges are unique by slightly adjusting them
                unique_edges = []
                for i, edge in enumerate(percentiles):
                    if i == 0 or edge > unique_edges[-1]:
                        unique_edges.append(edge)
                    else:
                        unique_edges.append(unique_edges[-1] + 0.1)  # Small increment

                if len(unique_edges) < 2:
                    # Still not enough unique values
                    df_nonzero['Complexity Bin'] = f'All\n({int(percentiles[0])}-{int(percentiles[-1])})'
                else:
                    # Create labels based on actual unique edges
                    n_bins = len(unique_edges) - 1
                    if n_bins == 1:
                        bin_labels = [f'All\n({int(unique_edges[0])}-{int(unique_edges[1])})']
                    elif n_bins == 2:
                        bin_labels = [
                            f'Low\n({int(unique_edges[0])}-{int(unique_edges[1])})',
                            f'High\n({int(unique_edges[1])+1}-{int(unique_edges[2])})'
                        ]
                    else:  # n_bins >= 3
                        bin_labels = [
                            f'Low\n({int(unique_edges[0])}-{int(unique_edges[1])})',
                            f'Mid\n({int(unique_edges[1])+1}-{int(unique_edges[2])})',
                            f'High\n({int(unique_edges[2])+1}-{int(unique_edges[3])})'
                        ]

                    df_nonzero['Complexity Bin'] = pd.cut(
                        df_nonzero[complexity_col],
                        bins=unique_edges,
                        labels=bin_labels,
                        include_lowest=True,
                        duplicates='drop'
                    )

        # Create the heatmap
        long_format_df = df_nonzero.melt(id_vars=['Complexity Bin'],
                                         value_vars=[f"{m}_accuracy" for m in model_cols],
                                         var_name='Model', value_name='Accuracy')
        long_format_df['Model'] = long_format_df['Model'].str.replace('_accuracy', '').str.replace('_response', '')
        long_format_df['Error Rate'] = 1 - long_format_df['Accuracy']

        # Remove any NaN complexity bins
        long_format_df = long_format_df.dropna(subset=['Complexity Bin'])

        if not long_format_df.empty:
            pivot_data = long_format_df.groupby(['Model', 'Complexity Bin'], observed=True)['Error Rate'].mean().unstack()
            if not pivot_data.empty:
                sns.heatmap(pivot_data, annot=True, fmt='.3f', cmap='Reds', ax=ax)
            else:
                ax.text(0.5, 0.5, 'No Valid Data', ha='center', va='center')
        else:
            ax.text(0.5, 0.5, 'No Valid Data', ha='center', va='center')

        ax.set_xlabel("Complexity Bin (Character Length)")

    _plot_heatmap('longest_tag_complexity', axes[0])
    axes[0].set_title('Error Rate by Longest Length of Tag Complexity')
    _plot_heatmap('shortest_tag_complexity', axes[1])
    axes[1].set_title('Error Rate by Shortest Length of Tag Complexity')

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_error_rate_heatmaps.png", dpi=300)
    plt.close()

def plot_correlation_analysis(df, model_cols, out_prefix):
    """Report 4: Correlation of all scores with tag complexity including p-values."""
    fig, axes = plt.subplots(1, 2, figsize=(18, 8))
    fig.suptitle('Metric Correlation with Tag Complexity (with p-values)', fontsize=16, weight='bold')

    def _plot_correlation_bars(complexity_col, ax):
        correlation_data = []

        for model in model_cols:
            for metric in ['accuracy', 'f1', 'precision', 'recall']:
                metric_col = f"{model}_{metric}"
                if metric_col in df.columns:
                    # Calculate correlation and p-value
                    valid_data = df[[complexity_col, metric_col]].dropna()
                    if len(valid_data) > 2:  # Need at least 3 points for correlation
                        corr_coef, p_value = pearsonr(valid_data[complexity_col], valid_data[metric_col])

                        correlation_data.append({
                            'Model': model.replace('_response', ''),
                            'Metric Type': metric.capitalize(),
                            'Correlation': corr_coef,
                            'P-value': p_value,
                            'Significant': p_value < 0.05
                        })

        if not correlation_data:
            ax.text(0.5, 0.5, 'No Data', ha='center', va='center')
            return

        correlation_df = pd.DataFrame(correlation_data)

        # Create the bar plot
        bars = sns.barplot(data=correlation_df, y='Model', x='Correlation',
                           hue='Metric Type', ax=ax, orient='h')

        # Add significance indicators and p-values as text
        for i, (_, row) in enumerate(correlation_df.iterrows()):
            # Get the bar for this data point
            bar = bars.patches[i]
            bar_width = bar.get_width()
            bar_height = bar.get_height()
            y_pos = bar.get_y() + bar_height/2

            # Add asterisk for significant correlations (red, at the end of bar)
            if row['Significant']:
                end_x = bar_width + (0.01 if bar_width >= 0 else -0.01)
                ax.text(end_x, y_pos, '*',
                        ha='left' if bar_width >= 0 else 'right', va='center',
                        fontsize=12, fontweight='bold', color='red')

            # Add p-value text - handle small bars differently
            p_text = f"p={row['P-value']:.3f}" if row['P-value'] >= 0.001 else "p<0.001"

            if abs(bar_width) > 0.05:  # Wide enough for text inside
                # Show p-value in white in the middle of the bar
                x_pos = bar_width / 2
                ax.text(x_pos, y_pos, p_text,
                        ha='center', va='center',
                        fontsize=8, color='white', fontweight='bold')
            else:
                # For very small bars, show p-value outside the bar
                if bar_width >= 0:
                    x_pos = bar_width + 0.02
                    ha = 'left'
                else:
                    x_pos = bar_width - 0.02
                    ha = 'right'

                ax.text(x_pos, y_pos, p_text,
                        ha=ha, va='center',
                        fontsize=8, color='black', alpha=0.8)

        ax.axvline(x=0, color='black', linestyle='--', alpha=0.7)
        ax.set_xlabel('Correlation Coefficient')

        # Add legend for significance
        ax.text(0.02, 0.98, '* p < 0.05', transform=ax.transAxes,
                ha='left', va='top', fontsize=10,
                bbox=dict(boxstyle='round,pad=0.3', facecolor='lightgray', alpha=0.7))

    _plot_correlation_bars('longest_tag_complexity', axes[0])
    axes[0].set_title('Correlation with Longest Tag Length')
    _plot_correlation_bars('shortest_tag_complexity', axes[1])
    axes[1].set_title('Correlation with Shortest Tag Length')

    plt.tight_layout(rect=[0, 0, 1, 0.96])
    plt.savefig(f"{out_prefix}_report_correlations.png", dpi=300, bbox_inches='tight')
    plt.close()

# --- Main Function ---
def main(results_csv, explanation_json, out_prefix="eval"):
    """Main evaluation pipeline."""
    sns.set_theme(style="whitegrid")
    print("🚀 Starting evaluation pipeline...")

    print("1. Loading and preparing data...")
    df = pd.read_csv(results_csv)
    model_cols = sorted([col for col in df.columns if col.endswith('_response')])
    print(f"   - Found models: {model_cols}")
    print(f"   - Dataset size: {len(df)} queries")

    print("2. Adding tag complexity from explanations...")
    df = add_tag_complexity_to_df(df, explanation_json, query_col="SPARQL Query")

    print("3. Evaluating model responses with detailed error analysis...")
    df = evaluate_model_responses(df, model_cols, answer_col="Answer")

    output_csv = f"{out_prefix}_results_with_metrics.csv"
    df.to_csv(output_csv, index=False)
    print(f"4. Saved detailed results to: {output_csv}")

    # Print summary of new columns added
    new_cols = []
    for model in model_cols:
        new_cols.extend([f"{model}_missing", f"{model}_extra", f"{model}_correct"])
    print(f"   - Added detailed analysis columns: {len(new_cols)} new columns")
    print(f"   - Column examples: {new_cols[:3]}...")

    print("5. Generating and saving visualization reports...")
    plot_overall_scores(df, model_cols, out_prefix)
    plot_type_analysis(df, model_cols, out_prefix)
    plot_error_rate_analysis(df, model_cols, out_prefix)
    plot_correlation_analysis(df, model_cols, out_prefix)

    print(f"✅ Analysis complete! Four report images and detailed CSV saved with prefix '{out_prefix}'.")

if __name__ == "__main__":
    RESULTS_CSV = "./output/llm_reasoning_results/family_1hop_results_sparql_ttl.csv"
    EXPLANATION_JSON = "./output/explanations_1hop.json"
    OUTPUT_PREFIX = "./output/llm_reasoning_results/family_1hop_sparql_evaluation"
    main(results_csv=RESULTS_CSV, explanation_json=EXPLANATION_JSON, out_prefix=OUTPUT_PREFIX)