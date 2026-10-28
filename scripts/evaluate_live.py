"""Evaluate live accounts only after explicit CLI confirmation."""

import argparse
import csv
import datetime
import json
import os
import pathlib

from investigate import run


def main():
    """Run the CLI command without performing I/O during import."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--confirm-live", action="store_true")
    parser.add_argument("--cases", default="evaluation/cases.json")
    args = parser.parse_args()
    if not args.confirm_live:
        parser.error("--confirm-live is required; real calls consume quota")
    cases = json.loads(pathlib.Path(args.cases).read_text(encoding="utf-8"))
    results = []
    for case in cases:
        item = run(
            os.getenv("INVESTIGATOR_URL", "http://127.0.0.1:18092"),
            os.environ["OPERATOR_TOKEN"],
            case["question"],
        )
        results.append(
            {
                "case": case["id"],
                "expectations": case["review"],
                "investigation": item,
            }
        )
    root = pathlib.Path("reports")
    root.mkdir(exist_ok=True)
    (root / "live-evaluation.json").write_text(
        json.dumps(
            {
                "kind": "live-evaluation-for-manual-review",
                "createdAt": datetime.datetime.now(datetime.UTC).isoformat(),
                "results": results,
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    with (root / "manual-review.csv").open(
        "w", newline="", encoding="utf-8"
    ) as file:
        writer = csv.writer(file)
        writer.writerow(
            [
                "case",
                "useful_queries_0_2",
                "uses_results_0_2",
                "grounding_0_2",
                "uncertainty_0_2",
                "rag_relevance_0_2",
                "notes",
            ]
        )
        for result in results:
            writer.writerow([result["case"], "", "", "", "", "", ""])
    print(
        "Saved actual responses for manual review; "
        "no quality score has been inferred."
    )


if __name__ == "__main__":
    main()
