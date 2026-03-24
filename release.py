#!/usr/bin/env python3
"""
Bump femtojar version and prepare a release.

This script:
1. Reads current version from pom.xml
2. Bumps major/minor/patch version
3. Updates root pom.xml, README.md, and example-project/pom.xml
4. Runs tests and builds artifacts
5. Optionally deploys with Maven release profile
6. Commits, tags, and optionally pushes
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import Tuple


class ReleaseManager:
    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.pom_xml = project_root / "pom.xml"
        self.readme = project_root / "README.md"
        self.example_pom = project_root / "example-project" / "pom.xml"

    def get_current_version(self) -> str:
        content = self.pom_xml.read_text(encoding="utf-8")
        match = re.search(r"<version>([^<]+)</version>", content)
        if not match:
            raise ValueError("Could not find version in pom.xml")
        return match.group(1)

    @staticmethod
    def parse_version(version: str) -> Tuple[int, int, int]:
        parts = version.split(".")
        if len(parts) == 2:
            return int(parts[0]), int(parts[1]), 0
        if len(parts) == 3:
            return int(parts[0]), int(parts[1]), int(parts[2])
        raise ValueError(f"Unsupported version format: {version}")

    def bump_version(self, current: str, bump: str) -> str:
        major, minor, patch = self.parse_version(current)
        if bump == "major":
            return f"{major + 1}.0.0"
        if bump == "minor":
            return f"{major}.{minor + 1}.0"
        if bump == "patch":
            return f"{major}.{minor}.{patch + 1}"
        raise ValueError(f"Unknown bump kind: {bump}")

    def update_pom_version(self, old: str, new: str) -> None:
        content = self.pom_xml.read_text(encoding="utf-8")
        updated = content.replace(f"<version>{old}</version>", f"<version>{new}</version>", 1)
        self.pom_xml.write_text(updated, encoding="utf-8")

    def update_readme_versions(self, old: str, new: str) -> None:
        if not self.readme.exists():
            return
        content = self.readme.read_text(encoding="utf-8")
        content = content.replace(f"<version>{old}</version>", f"<version>{new}</version>")
        content = content.replace(
            f"target/femtojar-{old}-cli.jar",
            f"target/femtojar-{new}-cli.jar",
        )
        self.readme.write_text(content, encoding="utf-8")

    def update_example_plugin_version(self, old: str, new: str) -> None:
        if not self.example_pom.exists():
            return
        content = self.example_pom.read_text(encoding="utf-8")
        pattern = (
            r"(<groupId>me\\.bechberger</groupId>\\s*"
            r"<artifactId>femtojar</artifactId>\\s*"
            r"<version>)" + re.escape(old) + r"(</version>)"
        )
        updated = re.sub(pattern, r"\g<1>" + new + r"\g<2>", content, flags=re.DOTALL)
        self.example_pom.write_text(updated, encoding="utf-8")

    def run_command(self, cmd: list[str], desc: str) -> None:
        print(f"\n-> {desc}")
        print("   $ " + " ".join(cmd))
        result = subprocess.run(cmd, cwd=self.project_root)
        if result.returncode != 0:
            raise RuntimeError(f"Command failed: {' '.join(cmd)}")

    def run_checks(self, include_its: bool) -> None:
        self.run_command(["mvn", "test"], "Running unit tests")
        if include_its:
            self.run_command(["mvn", "-Prun-its", "verify"], "Running integration tests")
        self.run_command(["mvn", "package", "-DskipTests"], "Building artifacts")

    def deploy(self) -> None:
        self.run_command(["mvn", "clean", "deploy", "-P", "release"], "Deploying release")

    def git_commit_tag(self, version: str) -> None:
        files = [
            "pom.xml",
            "README.md",
            "example-project/pom.xml",
        ]
        self.run_command(["git", "add", *files], "Staging release files")
        self.run_command(["git", "commit", "-m", f"Release {version}"], "Creating release commit")
        self.run_command(["git", "tag", "-a", f"v{version}", "-m", f"Release {version}"], "Tagging release")

    def git_push(self) -> None:
        self.run_command(["git", "push"], "Pushing commits")
        self.run_command(["git", "push", "--tags"], "Pushing tags")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Release femtojar")
    parser.add_argument("--major", action="store_true", help="Bump major version")
    parser.add_argument("--minor", action="store_true", help="Bump minor version (default)")
    parser.add_argument("--patch", action="store_true", help="Bump patch version")
    parser.add_argument("--no-its", action="store_true", help="Skip run-its integration tests")
    parser.add_argument("--no-deploy", action="store_true", help="Skip Maven deploy")
    parser.add_argument("--no-push", action="store_true", help="Skip git push")
    parser.add_argument("--dry-run", action="store_true", help="Show planned changes only")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    root = Path(__file__).resolve().parent
    manager = ReleaseManager(root)

    bump = "minor"
    if args.major:
        bump = "major"
    elif args.patch:
        bump = "patch"

    current = manager.get_current_version()
    new = manager.bump_version(current, bump)

    print(f"Current version: {current}")
    print(f"Next version:    {new}")

    if args.dry_run:
        print("\nDry run only. Files that would be updated:")
        print("- pom.xml")
        print("- README.md")
        print("- example-project/pom.xml")
        return

    try:
        manager.update_pom_version(current, new)
        manager.update_readme_versions(current, new)
        manager.update_example_plugin_version(current, new)

        manager.run_checks(include_its=not args.no_its)

        if not args.no_deploy:
            manager.deploy()

        manager.git_commit_tag(new)

        if not args.no_push:
            manager.git_push()

        print("\nRelease completed successfully.")
        print(f"Version: {new}")
        print("Artifacts:")
        print(f"- target/femtojar-{new}.jar")
        print(f"- target/femtojar-{new}-cli.jar")
    except Exception as exc:
        print(f"\nRelease failed: {exc}")
        print("You may want to revert local version changes manually if needed.")
        sys.exit(1)


if __name__ == "__main__":
    main()
