#!/usr/bin/env python3
"""
Bump femtojar version and prepare a release.

This script:
1. Reads current version from pom.xml
2. Bumps major/minor/patch version
3. Uses CHANGELOG.md Unreleased section as release notes and rolls it into a versioned entry
4. Updates root pom.xml, README.md, and example-project/pom.xml
5. Runs tests and builds artifacts with Maven release profile
6. Deploys to Maven repositories
7. Commits, tags, and optionally pushes
8. Creates a GitHub release (requires gh CLI)

Also supports --github-release-only to create a GitHub release for the current version
without bumping versions or making other changes.
"""

from __future__ import annotations

import argparse
from datetime import date
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, Tuple


class ReleaseManager:
    def __init__(self, project_root: Path):
        self.project_root = project_root
        self.pom_xml = project_root / "pom.xml"
        self.readme = project_root / "README.md"
        self.example_pom = project_root / "example-project" / "pom.xml"
        self.changelog = project_root / "CHANGELOG.md"
        self.jbang_catalog = project_root / "jbang-catalog.json"
        self._release_files = [
            self.pom_xml,
            self.readme,
            self.example_pom,
            self.changelog,
            self.jbang_catalog,
        ]

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

    def update_jbang_catalog(self, new_version: str) -> None:
        if not self.jbang_catalog.exists():
            return

        content = self.jbang_catalog.read_text(encoding="utf-8")
        data = json.loads(content)

        try:
            data["aliases"]["femtojar"]["script-ref"] = (
                f"https://github.com/parttimenerd/femtojar/releases/download/v{new_version}/"
                f"femtojar-{new_version}.jar"
            )
        except (KeyError, TypeError):
            raise ValueError("jbang-catalog.json has unexpected structure")

        self.jbang_catalog.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    def update_changelog_for_release(self, new_version: str) -> None:
        if not self.changelog.exists():
            raise FileNotFoundError("CHANGELOG.md not found")

        content = self.changelog.read_text(encoding="utf-8")
        lines = content.splitlines()

        unreleased_idx = None
        for idx, line in enumerate(lines):
            if line.strip() == "## [Unreleased]":
                unreleased_idx = idx
                break

        if unreleased_idx is None:
            raise ValueError("CHANGELOG.md must contain a '## [Unreleased]' section")

        next_section_idx = len(lines)
        for idx in range(unreleased_idx + 1, len(lines)):
            if lines[idx].startswith("## "):
                next_section_idx = idx
                break

        unreleased_body = "\n".join(lines[unreleased_idx + 1:next_section_idx]).strip()
        if not unreleased_body:
            raise ValueError("CHANGELOG.md Unreleased section is empty; add release notes before releasing")

        release_header = f"## [{new_version}] - {date.today().isoformat()}"
        unreleased_template = "\n".join([
            "### Added",
            "",
            "### Changed",
            "",
            "### Deprecated",
            "",
            "### Removed",
            "",
            "### Fixed",
            "",
            "### Security",
            "",
        ])
        replacement = f"## [Unreleased]\n\n{unreleased_template}\n{release_header}"

        updated = content.replace("## [Unreleased]", replacement, 1)
        updated = self._update_changelog_compare_links(updated, new_version)
        self.changelog.write_text(updated, encoding="utf-8")

    @staticmethod
    def _update_changelog_compare_links(content: str, new_version: str) -> str:
        unreleased_link_re = re.compile(r"^\[Unreleased\]:\s+(?P<url>.+)/compare/v(?P<old>[\d.]+)\.\.\.HEAD\s*$", re.MULTILINE)
        match = unreleased_link_re.search(content)
        if not match:
            return content

        base_url = match.group("url")
        old_version = match.group("old")
        new_links = (
            f"[Unreleased]: {base_url}/compare/v{new_version}...HEAD\n"
            f"[{new_version}]: {base_url}/compare/v{old_version}...v{new_version}"
        )
        return unreleased_link_re.sub(new_links, content, count=1)

    def preview_changelog_release(self, new_version: str) -> str:
        if not self.changelog.exists():
            return "- CHANGELOG.md not found"

        lines = self.changelog.read_text(encoding="utf-8").splitlines()

        unreleased_idx = None
        for idx, line in enumerate(lines):
            if line.strip() == "## [Unreleased]":
                unreleased_idx = idx
                break

        if unreleased_idx is None:
            return "- CHANGELOG.md missing '## [Unreleased]' section"

        next_section_idx = len(lines)
        for idx in range(unreleased_idx + 1, len(lines)):
            if lines[idx].startswith("## "):
                next_section_idx = idx
                break

        body = "\n".join(lines[unreleased_idx + 1:next_section_idx]).strip()
        if not body:
            return "- CHANGELOG.md Unreleased is empty"

        preview_lines = [
            f"- CHANGELOG.md: create ## [{new_version}] - {date.today().isoformat()} from current Unreleased notes",
            "- CHANGELOG.md: reset Unreleased to standard section template",
        ]

        non_empty_lines = [line for line in body.splitlines() if line.strip()][:4]
        if non_empty_lines:
            preview_lines.append("- Changelog content preview:")
            for line in non_empty_lines:
                preview_lines.append(f"  {line}")
        return "\n".join(preview_lines)

    def snapshot_files(self) -> Dict[Path, str]:
        snapshots: Dict[Path, str] = {}
        for file in self._release_files:
            if file.exists():
                snapshots[file] = file.read_text(encoding="utf-8")
        return snapshots

    @staticmethod
    def restore_snapshots(snapshots: Dict[Path, str]) -> None:
        for path, content in snapshots.items():
            path.write_text(content, encoding="utf-8")

    def run_command(self, cmd: list[str], desc: str) -> None:
        print(f"\n-> {desc}")
        print("   $ " + " ".join(cmd))
        result = subprocess.run(cmd, cwd=self.project_root)
        if result.returncode != 0:
            raise RuntimeError(f"Command failed: {' '.join(cmd)}")

    def run_checks(self, include_its: bool) -> None:
        self.run_command(["mvn", "-P", "release", "test"], "Running unit tests")
        if include_its:
            self.run_command(["mvn", "-P", "release,run-its", "verify"], "Running integration tests")
        self.run_command(["mvn", "-P", "release", "package", "-DskipTests"], "Building artifacts")

    def deploy(self) -> None:
        self.run_command(["mvn", "clean", "deploy", "-P", "release"], "Deploying release")

    def git_commit_tag(self, version: str) -> None:
        files = [
            "pom.xml",
            "README.md",
            "example-project/pom.xml",
            "CHANGELOG.md",
            "jbang-catalog.json",
        ]
        self.run_command(["git", "add", *files], "Staging release files")
        self.run_command(["git", "commit", "-m", f"Release {version}"], "Creating release commit")
        self.run_command(["git", "tag", "-a", f"v{version}", "-m", f"Release {version}"], "Tagging release")

    def git_push(self) -> None:
        self.run_command(["git", "push"], "Pushing commits")
        self.run_command(["git", "push", "-f", "--tags"], "Pushing tags")

    def create_github_release(self, version: str) -> None:
        """Create a GitHub release with formatted notes and JAR assets."""
        tag = f"v{version}"
        
        # Get changelog entry for this specific version
        changelog_entry = self.get_version_changelog_entry(version)
        if not changelog_entry:
            changelog_entry = f"Release {version}\n\nSee [CHANGELOG.md](https://github.com/parttimenerd/femtojar/blob/main/CHANGELOG.md) for details."

        # Format release notes
        release_notes = f"""{changelog_entry}

## Maven

### Plugin
```xml
<plugin>
  <groupId>me.bechberger</groupId>
  <artifactId>femtojar</artifactId>
  <version>{version}</version>
  <executions>
    <execution>
      <id>recompress-jar</id>
      <phase>package</phase>
      <goals>
        <goal>reencode-jars</goal>
      </goals>
    </execution>
  </executions>
  <configuration>
    <jars>
      <jar>
        <in>${{project.build.finalName}}.jar</in>
      </jar>
    </jars>
  </configuration>
</plugin>
```

### CLI
```bash
java -jar femtojar-{version}-cli.jar app.jar app-optimized.jar
```
"""

        # Create release notes file
        notes_file = self.project_root / '.release-notes.md'
        notes_file.write_text(release_notes)

        try:
            # Build jar paths
            jar_path = self.project_root / 'target' / f'femtojar-{version}.jar'
            assets = []
            if jar_path.exists():
                assets.append(str(jar_path))
            else:
                print(f"⚠️  JAR not found at {jar_path}")

            create_cmd = ['gh', 'release', 'create', tag,
                          '--title', f'Release {version}',
                          '--notes-file', str(notes_file)] + assets
            
            if not assets:
                print("⚠️  No JAR assets found, creating release without assets")

            # If the release already exists (e.g. rerun), fall back to uploading assets
            try:
                self.run_command(create_cmd, "Creating GitHub release")
            except RuntimeError:
                if assets:
                    upload_cmd = ['gh', 'release', 'upload', tag, '--clobber'] + assets
                    self.run_command(upload_cmd, "Uploading GitHub release assets")
                else:
                    raise
        except Exception as e:
            print(f"⚠️  Failed to create GitHub release: {e}")
            print("   The git tag was created, but the GitHub release was not.")
            print("   You can create it manually at: https://github.com/parttimenerd/femtojar/releases")
        finally:
            # Clean up notes file
            if notes_file.exists():
                notes_file.unlink()

    def get_version_changelog_entry(self, version: str) -> str:
        """Extract changelog entry for a specific released version."""
        if not self.changelog.exists():
            return ""

        content = self.changelog.read_text(encoding="utf-8")
        
        # Look for [version] section
        match = re.search(
            rf'## \[{re.escape(version)}\][^\n]*\n(.*?)(?=\n## \[|$)',
            content,
            re.DOTALL
        )
        
        if match:
            return match.group(1).strip()
        return ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Release femtojar")
    parser.add_argument("--major", action="store_true", help="Bump major version")
    parser.add_argument("--minor", action="store_true", help="Bump minor version (default)")
    parser.add_argument("--patch", action="store_true", help="Bump patch version")
    parser.add_argument("--no-its", action="store_true", help="Skip run-its integration tests")
    parser.add_argument("--no-push", action="store_true", help="Skip git push")
    parser.add_argument("--no-github-release", action="store_true", help="Skip GitHub release creation")
    parser.add_argument("--github-release-only", action="store_true", help="Create GitHub release for current version only (no version bump)")
    parser.add_argument("--dry-run", action="store_true", help="Show planned changes only")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    root = Path(__file__).resolve().parent
    manager = ReleaseManager(root)

    # Handle GitHub release only mode
    if args.github_release_only:
        version = manager.get_current_version()
        print(f"Creating GitHub release for current version: {version}")
        manager.create_github_release(version)
        print(f"\nGitHub release created for version {version}")
        return

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
        print("- jbang-catalog.json")
        print(manager.preview_changelog_release(new))
        return

    snapshots = manager.snapshot_files()
    try:
        manager.update_changelog_for_release(new)
        manager.update_pom_version(current, new)
        manager.update_readme_versions(current, new)
        manager.update_example_plugin_version(current, new)
        manager.update_jbang_catalog(new)

        manager.run_checks(include_its=not args.no_its)
        manager.deploy()

        manager.git_commit_tag(new)

        if not args.no_push:
            manager.git_push()

        if not args.no_github_release:
            manager.create_github_release(new)

        print("\nRelease completed successfully.")
        print(f"Version: {new}")
        print("Artifacts:")
        print(f"- target/femtojar-{new}.jar")
    except Exception as exc:
        manager.restore_snapshots(snapshots)
        print(f"\nRelease failed: {exc}")
        print("Local release edits were reverted automatically.")
        sys.exit(1)


if __name__ == "__main__":
    main()