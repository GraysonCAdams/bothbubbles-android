# Phase 1 Implementation: Documentation Fixes

## 1. Root README.md Updates

**Problem:** Hardcoded `JAVA_HOME` path specific to one user's machine.
**Fix:** Generalize the build instructions.

```markdown
### Build Commands

#### Prerequisites
- JDK 17 (Bundled with Android Studio or installed separately)
- Android SDK API 35

#### Setting JAVA_HOME
If you encounter JDK issues, ensure `JAVA_HOME` is set.
**macOS (Android Studio):**
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
# OR specific path:
# export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

**Windows/Linux:**
Set `JAVA_HOME` to your JDK 17 installation directory.
```

## 2. Schema README Updates

**Problem:** Hardcoded DB version number that drifts.
**Fix:** Point to source of truth.

```markdown
## Schema Files

Each JSON file represents a specific database version. Files are auto-generated when building the app and should be committed to version control.

**Current Version:** Please refer to `BothBubblesDatabase.kt` (constant `DATABASE_VERSION`) for the current version number.
```

## 3. Compose Best Practices Updates

**Problem:** Missing link to architecture decisions.
**Fix:** Add "Architecture Standards" section.

```markdown
## 5. Architecture Standards

We follow specific Architectural Decision Records (ADRs) for this project.
Please review them before refactoring or creating new components:

- [ADR 0001: Coordinator vs Delegates](../../refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_0001_coordinator_vs_delegate.md)
- [ADR 0003: UI Depends on Interfaces](../../refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_0003_ui_depends_on_interfaces.md)
- [ADR 0004: Delegate Lifecycle](../../refactor_plans/architecture_alignment/phase_0_shared_vision/ADR_0004_delegate_lifecycle_rules.md)
```
