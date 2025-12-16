# Phase 5 Audit Checklist

## Singleton service checklist

- [ ] No dependency on Compose/UI/ViewModels
- [ ] Uses `@ApplicationContext` if a `Context` is needed
- [ ] Coroutine scopes are bounded and cancelable
- [ ] Exposes state via `StateFlow` / `SharedFlow` with clear ownership

## Framework component checklist

- [ ] Minimal logic in `android.app.Service` / receivers
- [ ] Delegates to injected singleton logic
- [ ] Handles lifecycle cleanly (start/stop/cancel)
