# Android Auto Crash - Action List Exceeded

## Severity: HIGH (Crash when using Android Auto)

## Timestamp
- 2025-12-15 15:24:06

## Error
```
java.lang.RuntimeException: java.lang.IllegalArgumentException:
Action list exceeded max number of 0 actions with custom titles
```

## Stack Trace
```
Caused by: java.lang.IllegalArgumentException: Action list exceeded max number of 0 actions with custom titles
    at androidx.car.app.model.constraints.ActionsConstraints.validateOrThrow(ActionsConstraints.java:328)
    at androidx.car.app.model.ListTemplate$Builder.addAction(ListTemplate.java:455)
    at com.bothbubbles.services.auto.ConversationDetailScreen.onGetTemplate(ConversationDetailScreen.kt:326)
    at androidx.car.app.Screen.getTemplateWrapper(Screen.java:350)
    at androidx.car.app.ScreenManager.getTopTemplate(ScreenManager.java:287)
```

## Affected Component
- `ConversationDetailScreen.kt:326`
- Android Auto car app integration

## Root Cause Analysis
The Android Auto `ListTemplate` has constraints on how many actions with custom titles can be added. At line 326 of `ConversationDetailScreen.kt`, the code is trying to add an action to a template that doesn't allow any actions with custom titles (max = 0).

This appears to be a template/context mismatch where actions are being added where they shouldn't be.

## Recommended Fix
1. Check the template type being used in `ConversationDetailScreen.onGetTemplate()`
2. Ensure the correct template is used that supports actions
3. Add validation before adding actions to check constraints
4. Consider using `ItemList` actions instead of template-level actions

## Files to Check
- `app/src/main/kotlin/com/bothbubbles/services/auto/ConversationDetailScreen.kt`
- Line 326 specifically
