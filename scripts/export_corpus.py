"""Export versioned Markdown bytes and a verified checksum manifest."""

import argparse
import hashlib
import json
import subprocess
from pathlib import Path, PurePosixPath


def _git(repository: Path, *arguments: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(repository), *arguments])


def export_corpus(repository: Path, revision: str, output: Path) -> dict:
    """Export the exact Markdown bytes from a commit into a fresh directory."""
    repository = repository.resolve()
    commit = (
        _git(repository, "rev-parse", revision + "^{commit}").decode().strip()
    )
    if output.exists() and any(output.iterdir()):
        raise ValueError("output must be a new or empty directory")

    output.mkdir(parents=True, exist_ok=True)
    hashes = {}
    names = _git(
        repository, "ls-tree", "-r", "--name-only", commit, "--", "docs"
    )
    for name in names.decode().splitlines():
        if not name.endswith(".md"):
            continue
        relative = PurePosixPath(name).relative_to("docs")
        if ".." in relative.parts:
            raise ValueError("Invalid Git path")

        content = _git(repository, "show", commit + ":" + name)
        destination = output.joinpath(*relative.parts)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
        hashes[str(relative)] = hashlib.sha256(content).hexdigest()

    manifest = {"revision": commit, "files": hashes}
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    return {
        "revision": commit,
        "files": len(hashes),
        "path": str(output.resolve()),
    }


def main():
    """Export a requested revision without changing Git state."""
    parser = argparse.ArgumentParser()
    parser.add_argument("repository", type=Path)
    parser.add_argument("revision")
    parser.add_argument("--output", type=Path, default=Path(".tools/corpus"))
    arguments = parser.parse_args()
    try:
        result = export_corpus(
            arguments.repository, arguments.revision, arguments.output
        )
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(result))


if __name__ == "__main__":
    main()
