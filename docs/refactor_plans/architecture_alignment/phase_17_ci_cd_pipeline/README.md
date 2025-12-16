# Phase 17 — CI/CD Pipeline

> **Status**: Planned
> **Prerequisite**: All prior phases (11-16) complete — ensures quality gates have something to enforce

## Layman's Explanation

All our architecture improvements, tests, and quality standards mean nothing if we don't enforce them automatically. This phase sets up GitHub Actions to run lint, tests, and builds on every pull request, plus automated releases for production.

Once CI/CD is in place, quality standards are no longer optional — the pipeline gates ensure every merged PR meets our standards.

## Connection to Shared Vision

CI/CD is the enforcement mechanism for everything we've built. It ensures:
- Tests actually run (Phase 13)
- Lint rules are enforced (Phase 16)
- Secrets aren't committed (Phase 16)
- Builds don't break
- Releases are consistent

## Goals

1. **PR Checks**: Lint, detekt, unit tests, build verification on every PR
2. **Nightly Builds**: Full test suite, release build, dependency audit
3. **Release Automation**: Tag-based releases with Fastlane or Gradle Publishing
4. **Quality Gates**: Block merges that fail checks

## GitHub Actions Workflows

### 1. PR Checks Workflow

```yaml
# .github/workflows/pr-checks.yml
name: PR Checks

on:
  pull_request:
    branches: [master, main, develop]

concurrency:
  group: ${{ github.workflow }}-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  lint:
    name: Lint & Static Analysis
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Run ktlint
        run: ./gradlew ktlintCheck

      - name: Run detekt
        run: ./gradlew detekt

      - name: Run Android Lint
        run: ./gradlew lint

      - name: Check for secrets in code
        run: |
          if grep -rE "(api[_-]?key|password|secret)\s*=" --include="*.kt" app/src/; then
            echo "::error::Potential secret found in source code!"
            exit 1
          fi

  unit-tests:
    name: Unit Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Run unit tests
        run: ./gradlew test

      - name: Upload test results
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-results
          path: '**/build/reports/tests/'

  build:
    name: Build Debug APK
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Build debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk

  screenshot-tests:
    name: Screenshot Tests
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Run Paparazzi tests
        run: ./gradlew verifyPaparazziDebug

      - name: Upload screenshot diffs
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: screenshot-diffs
          path: '**/build/paparazzi/failures/'
```

### 2. Nightly Build Workflow

```yaml
# .github/workflows/nightly.yml
name: Nightly Build

on:
  schedule:
    - cron: '0 3 * * *'  # 3 AM UTC daily
  workflow_dispatch:  # Manual trigger

jobs:
  full-test-suite:
    name: Full Test Suite
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Run all tests
        run: ./gradlew test

      - name: Generate coverage report
        run: ./gradlew jacocoTestReport

      - name: Upload coverage
        uses: codecov/codecov-action@v3
        with:
          files: '**/build/reports/jacoco/test/jacocoTestReport.xml'

  release-build:
    name: Release Build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Build release APK
        run: ./gradlew assembleRelease
        env:
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}

      - name: Upload release APK
        uses: actions/upload-artifact@v4
        with:
          name: release-apk
          path: app/build/outputs/apk/release/app-release.apk

  dependency-audit:
    name: Dependency Audit
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Check for dependency updates
        run: ./gradlew dependencyUpdates -Drevision=release

      - name: Check for vulnerabilities
        run: ./gradlew dependencyCheckAnalyze
        continue-on-error: true  # Don't fail nightly for vulnerabilities

  ui-tests:
    name: Instrumented UI Tests
    runs-on: macos-latest  # Need macOS for Android emulator
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Run UI tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 31
          arch: x86_64
          script: ./gradlew connectedAndroidTest
```

### 3. Release Workflow

```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    name: Build and Release
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0  # Full history for changelog

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > app/keystore.jks

      - name: Build release APK
        run: ./gradlew assembleRelease
        env:
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}

      - name: Build release AAB
        run: ./gradlew bundleRelease
        env:
          SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
          SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
          SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}

      - name: Generate changelog
        id: changelog
        run: |
          # Get commits since last tag
          PREV_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")
          if [ -n "$PREV_TAG" ]; then
            CHANGELOG=$(git log $PREV_TAG..HEAD --pretty=format:"- %s")
          else
            CHANGELOG=$(git log --pretty=format:"- %s" -20)
          fi
          echo "changelog<<EOF" >> $GITHUB_OUTPUT
          echo "$CHANGELOG" >> $GITHUB_OUTPUT
          echo "EOF" >> $GITHUB_OUTPUT

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v1
        with:
          body: |
            ## Changes
            ${{ steps.changelog.outputs.changelog }}
          files: |
            app/build/outputs/apk/release/app-release.apk
            app/build/outputs/bundle/release/app-release.aab

      - name: Upload to Play Store (Internal Track)
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_STORE_SERVICE_ACCOUNT }}
          packageName: com.bothbubbles
          releaseFiles: app/build/outputs/bundle/release/app-release.aab
          track: internal
          status: completed
```

## Implementation Steps

### Step 1: Add Gradle Plugins (2h)

```kotlin
// build.gradle.kts (project)
plugins {
    id("io.gitlab.arturbosch.detekt") version "1.23.4" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.0.3" apply false
    id("org.owasp.dependencycheck") version "9.0.7" apply false
    id("com.github.ben-manes.versions") version "0.50.0" apply false
}

// build.gradle.kts (app)
plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.owasp.dependencycheck")
    id("com.github.ben-manes.versions")
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

ktlint {
    android.set(true)
    outputColorName.set("RED")
}
```

### Step 2: Create Config Files (2h)

```
.github/
├── workflows/
│   ├── pr-checks.yml
│   ├── nightly.yml
│   └── release.yml
├── CODEOWNERS
└── pull_request_template.md

config/
├── detekt/
│   └── detekt.yml
└── ktlint/
    └── .editorconfig
```

### Step 3: Configure Repository Settings (1h)

**Branch Protection Rules (Settings → Branches):**
- Require PR reviews before merging
- Require status checks to pass:
  - `Lint & Static Analysis`
  - `Unit Tests`
  - `Build Debug APK`
  - `Screenshot Tests`
- Require branches to be up to date

### Step 4: Set Up Secrets (1h)

**Required Repository Secrets:**
| Secret | Description |
|--------|-------------|
| `SIGNING_KEY_ALIAS` | Keystore alias |
| `SIGNING_KEY_PASSWORD` | Key password |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `KEYSTORE_BASE64` | Base64-encoded keystore |
| `PLAY_STORE_SERVICE_ACCOUNT` | Google Play API credentials |
| `CODECOV_TOKEN` | Codecov upload token |

### Step 5: Test Workflows (4h)

1. Create test PR to verify PR checks
2. Manually trigger nightly workflow
3. Create test tag to verify release workflow
4. Verify artifacts are uploaded correctly

### Step 6: Add Badges to README (30m)

```markdown
# BothBubbles

[![PR Checks](https://github.com/user/bothbubbles/actions/workflows/pr-checks.yml/badge.svg)](...)
[![Nightly](https://github.com/user/bothbubbles/actions/workflows/nightly.yml/badge.svg)](...)
[![codecov](https://codecov.io/gh/user/bothbubbles/branch/master/graph/badge.svg)](...)
```

## Exit Criteria

- [ ] PR checks workflow runs on all PRs
- [ ] All PR checks pass on current master
- [ ] Branch protection enabled
- [ ] Nightly workflow runs successfully
- [ ] Release workflow creates GitHub release
- [ ] APK/AAB artifacts uploaded correctly
- [ ] Play Store upload works (internal track)
- [ ] Coverage reports uploaded to Codecov
- [ ] Status badges added to README

## Inventory

| Task | Effort | Owner | Status |
|------|--------|-------|--------|
| Add Gradle plugins | 2h | _Unassigned_ | ☐ |
| Create detekt.yml config | 1h | _Unassigned_ | ☐ |
| Create ktlint config | 30m | _Unassigned_ | ☐ |
| Write pr-checks.yml | 2h | _Unassigned_ | ☐ |
| Write nightly.yml | 2h | _Unassigned_ | ☐ |
| Write release.yml | 2h | _Unassigned_ | ☐ |
| Configure branch protection | 1h | _Unassigned_ | ☐ |
| Set up repository secrets | 1h | _Unassigned_ | ☐ |
| Test PR workflow | 2h | _Unassigned_ | ☐ |
| Test nightly workflow | 1h | _Unassigned_ | ☐ |
| Test release workflow | 2h | _Unassigned_ | ☐ |
| Add README badges | 30m | _Unassigned_ | ☐ |

**Total Estimated Effort**: 16-18 hours

## Risks

- **Medium**: macOS runners for UI tests are expensive (use sparingly)
- **Medium**: Signing secrets must be kept secure
- **Low**: Workflow syntax errors — test incrementally

## Dependencies

- Phase 13 must be complete (tests exist to run)
- Phase 16 must be complete (lint rules, detekt configured)
- All tests must pass locally before CI enforcement

## Workflow Execution Time Targets

| Workflow | Target | Notes |
|----------|--------|-------|
| PR Checks | < 10 min | Parallel jobs help |
| Nightly | < 30 min | UI tests are slowest |
| Release | < 15 min | After build cache warm |

## Cost Considerations

| Resource | Free Tier | Notes |
|----------|-----------|-------|
| Ubuntu runners | 2,000 min/month | Most jobs |
| macOS runners | 200 min/month | UI tests only |
| Storage | 500 MB artifacts | Auto-expire old |

**Recommendation**: Run UI tests only in nightly builds to conserve macOS minutes.

## Next Steps

With Phase 17 complete, the enterprise-grade infrastructure is in place. Future work focuses on:
- Expanding test coverage
- Adding more quality gates
- Performance regression testing
- A/B testing infrastructure
