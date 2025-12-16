# Phase 1 Checklist — Docs Alignment

## Layman’s checklist

- If a doc says “we do X,” confirm the code does X.
- If a number changes often, don’t hardcode it in docs.
- If a doc teaches a pattern, link to an example file.

## Technical checklist

- [ ] Audit: references to `MessageSender` vs `MessageSendingService`
- [ ] Audit: delegate lifecycle instructions (`initialize(...)` vs factories)
- [ ] Audit: any mention of DB version numbers
- [ ] Audit: any “Services layer” descriptions that might imply Android `Service`
- [ ] Add: links to Phase 0 ADRs

## Candidate files

- [README.md](../../../README.md)
- [docs/COMPOSE_BEST_PRACTICES.md](../../COMPOSE_BEST_PRACTICES.md)
- [app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/README.md](../../../app/src/main/kotlin/com/bothbubbles/ui/chat/delegates/README.md)
- [app/schemas/README.md](../../../app/schemas/README.md)
