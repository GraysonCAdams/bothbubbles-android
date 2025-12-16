# Stage 1: ConversationsScreen Refactoring Plan

**Goal:** Decompose `ConversationsScreen.kt` (1353 lines) into smaller components to separate list rendering, header UI, and complex gesture logic.

**Source File:** `app/src/main/kotlin/com/bothbubbles/ui/conversations/ConversationsScreen.kt`

## 1. Extract `ConversationsList.kt`
**Responsibility:** Render the list of conversations.
**Input:** List of conversations, scroll state, callbacks.
**Action:**
- Move the `LazyColumn` and `itemsIndexed` logic here.
- Include `SwipeableConversationTile` logic (or keep it separate if it's already separate).

## 2. Extract `ConversationsTopBar.kt`
**Responsibility:** Render the top app bar and selection mode header.
**Input:** Selection state, filter state, callbacks.
**Action:**
- Move the `TopAppBar` and `SelectionModeHeader` (if inline) here.
- Move `FilterDropdown` logic here.

## 3. Extract `PinnedConversations.kt`
**Responsibility:** Render pinned conversations and handle drag-and-drop.
**Input:** List of pinned conversations, drag state.
**Action:**
- Move `PinnedConversationsRow` and the associated drag overlay `Box` logic here.
- Isolate the complex drag offset calculations.

## 4. Extract `PullToSearchLogic.kt`
**Responsibility:** Handle the custom pull-to-search gesture.
**Input:** Scroll state, callback to trigger search.
**Action:**
- Move the `NestedScrollConnection` object and its logic here.
- Move the `PullToSearchIndicator` composable here.

## Execution Order
1.  Create `PullToSearchLogic.kt` and move the nested scroll connection.
2.  Create `PinnedConversations.kt` and move pinned row logic.
3.  Create `ConversationsTopBar.kt` and move header UI.
4.  Create `ConversationsList.kt` and move the main list.
5.  Update `ConversationsScreen.kt` to coordinate these components.
