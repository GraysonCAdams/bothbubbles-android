# Enforcement Strategy for Phase 0

To ensure these ADRs are not just documentation but actual rules, we implement the following checks:

## 1. Pull Request Template Update

Add this checklist to `.github/PULL_REQUEST_TEMPLATE.md`:

```markdown
## Architecture Checks
- [ ] I have not introduced a global event bus (ADR 0002)
- [ ] UI components depend on Interfaces, not concrete Services (ADR 0003)
- [ ] Delegates are initialized via Constructor/Factory, not `lateinit` (ADR 0004)
```

## 2. Code Review Guidelines

Reviewers should specifically look for:

*   **"Manager" vs "Service" naming:** If a class is just logic, it shouldn't be named `Service` if it confuses the Android definition. (Though we accepted `MessageSendingService` for now, new classes should be `Manager` or `UseCase`).
*   **`lateinit var` in ViewModels/Delegates:** Immediate block. Ask for `AssistedInject`.
*   **`((String) -> Unit)?` callbacks:** Ask for a defined Interface or a `Flow`.

## 3. Linting (Future)

Eventually, we can write custom Detekt rules to enforce:
*   No `lateinit var` in classes ending with `Delegate`.
*   No public `initialize()` methods in classes ending with `Delegate`.
