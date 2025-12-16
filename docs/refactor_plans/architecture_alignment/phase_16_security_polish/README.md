# Phase 16 — Security & Polish

> **Status**: Planned
> **Prerequisite**: Phase 15 (Feature Module Extraction) — modularization complete

## Layman's Explanation

Before we set up automated quality gates (CI/CD), we need to ensure the codebase meets security and quality standards. This phase addresses security concerns (secrets management, API key storage), finalizes documentation, and performs quality audits.

Think of this as the final inspection before the house is ready for the building inspector (CI/CD).

## Connection to Shared Vision

Security and polish ensure our "enterprise-grade" standards are met. Secrets in code are a liability. Incomplete documentation creates maintenance burden. This phase closes those gaps.

## Goals

1. **Secrets Management**: Move all API keys and secrets to secure storage
2. **Security Audit**: Review for common vulnerabilities (OWASP Mobile Top 10)
3. **Documentation Finalization**: Ensure all modules have README.md files
4. **Accessibility Audit**: Verify content descriptions, touch targets
5. **Performance Baseline**: Establish benchmarks before CI enforcement

## Scope

### 1. Secrets Management

| Secret | Current Location | Target |
|--------|------------------|--------|
| GIF API key | Hardcoded in `GifRepository.kt` | `local.properties` + BuildConfig |
| Server URL | User input, stored in DataStore | ✅ OK |
| Server password | User input, stored in DataStore | Consider Android Keystore |

**Implementation:**

```kotlin
// local.properties (gitignored)
GIF_API_KEY=your_api_key_here

// build.gradle.kts
android {
    defaultConfig {
        buildConfigField("String", "GIF_API_KEY",
            "\"${project.findProperty("GIF_API_KEY") ?: ""}\"")
    }
}

// GifRepository.kt
private val apiKey = BuildConfig.GIF_API_KEY
```

**CI Protection:**
```yaml
# .github/workflows/secrets-check.yml
- name: Check for secrets
  run: |
    if grep -rE "(api[_-]?key|password|secret)" --include="*.kt" app/src/; then
      echo "Potential secret found in source code!"
      exit 1
    fi
```

### 2. Security Audit Checklist

| Category | Check | Status | Notes |
|----------|-------|--------|-------|
| **Data Storage** | | | |
| | Sensitive data not logged | ☐ | Review Timber calls |
| | Passwords stored with Android Keystore | ☐ | Optional enhancement |
| | Database not exported in manifest | ☐ | `android:exported="false"` |
| **Network** | | | |
| | HTTPS enforced | ☐ | Check OkHttp config |
| | Certificate pinning (optional) | ☐ | For high-security |
| | No sensitive data in URLs | ☐ | |
| **Input Validation** | | | |
| | User input sanitized | ☐ | Message content, search |
| | Deep links validated | ☐ | |
| **Code** | | | |
| | No hardcoded secrets | ☐ | After migration |
| | ProGuard/R8 enabled for release | ☐ | Check config |
| | Debuggable false in release | ☐ | |

### 3. Documentation Finalization

**Required READMEs:**

| Module | README Exists | Complete | Status |
|--------|---------------|----------|--------|
| `:app` | ☐ | ☐ | |
| `:core:model` | ☐ | ☐ | |
| `:core:network` | ☐ | ☐ | |
| `:core:data` | ☐ | ☐ | |
| `:core:design` | ☐ | ☐ | |
| `:feature:chat` | ☐ | ☐ | |
| `:feature:conversations` | ☐ | ☐ | |
| `:feature:settings` | ☐ | ☐ | |
| `:feature:setup` | ☐ | ☐ | |
| `:navigation` | ☐ | ☐ | |

**README Template:**
```markdown
# Module Name

## Purpose
Brief description of what this module does.

## Dependencies
- `:core:model` - Entity definitions
- ...

## Public API
Key classes and interfaces exposed by this module.

## Internal Structure
```
src/main/kotlin/
├── di/         # Hilt modules
├── ...
```

## Testing
How to run tests for this module.
```

### 4. Accessibility Audit

**Compose Accessibility Checklist:**

| Check | Files Affected | Status |
|-------|----------------|--------|
| All clickable items have contentDescription | All composables | ☐ |
| Touch targets ≥ 48dp | Buttons, icons | ☐ |
| Color contrast meets WCAG AA | Theme colors | ☐ |
| Screen reader navigation logical | All screens | ☐ |
| Focus indicators visible | Interactive elements | ☐ |

**Common Fixes:**
```kotlin
// Before
Icon(Icons.Default.Send, null)

// After
Icon(
    Icons.Default.Send,
    contentDescription = stringResource(R.string.send_message)
)

// Touch target
IconButton(
    onClick = { ... },
    modifier = Modifier.size(48.dp)  // Minimum touch target
) { ... }
```

### 5. Performance Baseline

**Metrics to Establish:**

| Metric | Target | Current | Notes |
|--------|--------|---------|-------|
| Cold start time | < 2s | _Measure_ | |
| Warm start time | < 1s | _Measure_ | |
| Message list scroll | 60 fps | _Measure_ | |
| Memory usage (idle) | < 100 MB | _Measure_ | |
| APK size | < 30 MB | _Measure_ | |

**How to Measure:**
```bash
# App startup
adb shell am start-activity -W com.bothbubbles/.MainActivity

# Memory
adb shell dumpsys meminfo com.bothbubbles

# APK size
ls -lh app/build/outputs/apk/release/app-release.apk
```

### 6. Code Quality Final Checks

**Lint Rules to Enable:**

```kotlin
// build.gradle.kts
android {
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true

        // Enable specific checks
        enable += listOf(
            "InvalidPackage",
            "MissingTranslation",
            "ExtraTranslation",
            "Accessibility",
            "HardcodedText"
        )
    }
}
```

**Detekt Configuration:**
```yaml
# detekt.yml
complexity:
  LongMethod:
    threshold: 60
  LargeClass:
    threshold: 600
  ComplexCondition:
    threshold: 4

style:
  MagicNumber:
    active: true
    ignoreNumbers: ['-1', '0', '1', '2']
  MaxLineLength:
    maxLineLength: 120

potential-bugs:
  CastToNullableType:
    active: true
```

## Implementation Steps

### Step 1: Secrets Migration (4-6h)

1. Add `local.properties` entries for API keys
2. Update `build.gradle.kts` with BuildConfig fields
3. Update code to read from BuildConfig
4. Add `.gitignore` entries
5. Add CI secrets check

### Step 2: Security Audit (6-8h)

1. Run through security checklist
2. Fix identified issues
3. Review ProGuard rules
4. Test release build

### Step 3: Module Documentation (4-6h)

1. Create README.md for each module
2. Document public API
3. Add architecture diagrams where helpful
4. Update main project README

### Step 4: Accessibility Audit (6-8h)

1. Run TalkBack through all screens
2. Add missing contentDescriptions
3. Fix touch target sizes
4. Verify color contrast
5. Test with Accessibility Scanner app

### Step 5: Performance Baseline (4-6h)

1. Measure all metrics
2. Document baselines
3. Identify any immediate issues
4. Create benchmark tests (optional)

### Step 6: Code Quality Setup (2-4h)

1. Configure lint rules
2. Add detekt to Gradle
3. Run full analysis
4. Fix critical issues

## Exit Criteria

### Secrets
- [ ] No API keys in source code
- [ ] `local.properties` template exists
- [ ] CI secrets check configured

### Security
- [ ] Security checklist completed
- [ ] All critical issues fixed
- [ ] Release build tested

### Documentation
- [ ] All modules have README.md
- [ ] Main README updated
- [ ] Architecture diagrams current

### Accessibility
- [ ] All screens pass TalkBack test
- [ ] Touch targets ≥ 48dp
- [ ] Content descriptions present

### Performance
- [ ] Baseline metrics documented
- [ ] No critical performance issues
- [ ] APK size acceptable

### Code Quality
- [ ] Lint passes without errors
- [ ] Detekt passes without critical issues

## Inventory

| Task | Effort | Owner | Status |
|------|--------|-------|--------|
| Secrets migration | 4-6h | _Unassigned_ | ☐ |
| Security audit | 6-8h | _Unassigned_ | ☐ |
| Module documentation (10 modules) | 4-6h | _Unassigned_ | ☐ |
| Accessibility audit | 6-8h | _Unassigned_ | ☐ |
| Performance baseline | 4-6h | _Unassigned_ | ☐ |
| Code quality setup | 2-4h | _Unassigned_ | ☐ |

**Total Estimated Effort**: 28-38 hours

## Risks

- **Low**: Most tasks are auditing and documentation
- **Medium**: Accessibility fixes may require design input
- **Low**: Performance issues unlikely to be blocking

## Dependencies

- Phase 15 should be complete (all modules exist)
- No dependency on CI/CD (but prepares for it)

## Next Steps

After Phase 16, the codebase is secure, documented, and quality-audited. Phase 17 (CI/CD Pipeline) can then automate enforcement of these standards.
