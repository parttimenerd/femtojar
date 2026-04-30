# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## [0.1.6] - 2026-04-30

### Added
- Default phase (`package`) set on the `reencode-jars` goal — `<phase>` no longer needed in executions
- Auto-detection of project JAR when `<jars>` configuration is omitted (defaults to `${project.build.finalName}.jar`)

## [0.1.4] - 2026-04-10

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## [0.1.3] - 2026-04-10

### Fixed
- Maven settings related bug caused by making a class a record

### Security

## [0.1.2] - 2026-04-10

### Fixed

- Resource handling with custom URL handler

## [0.1.1] - 2026-03-27

- Add [ProGuard](https://www.guardsquare.com/proguard) support

### Added

- Initial release