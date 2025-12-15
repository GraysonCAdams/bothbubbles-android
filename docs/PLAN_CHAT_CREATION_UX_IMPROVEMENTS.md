# Plan: Chat Creation UX Improvements (Material Design 3)

## 1. Executive Summary

Refactor the Chat Creation flow to fully align with Material Design 3 (M3) guidelines, improve accessibility, and simplify the user journey for both 1:1 and group chats. The goal is to make the "New Chat" experience feel native to modern Android while retaining the powerful features of BlueBubbles.

## 2. Current Pain Points

- **Inconsistent Entry Points:** Separate screens for `ChatCreator` and `GroupCreator` create code duplication and slightly different UX.
- **Hidden Group Creation:** The "select one to enable multi-select" pattern is less discoverable than a dedicated "Create Group" option.
- **Custom Components:** The "To:" field with chips is a custom implementation that doesn't fully leverage M3 `TextField` or `SearchBar` semantics.
- **Visual Hierarchy:** Lack of M3 elevation and dynamic color usage in the contact list.

## 3. Proposed UX Flow (Google Messages / M3 Style)

### A. Unified "New Conversation" Screen

_Replaces `ChatCreatorScreen`_

**Layout:**

1.  **Top App Bar:** `CenterAlignedTopAppBar` with title "New conversation".
2.  **Search/Recipient Field:**
    - **State A (Empty):** A "To" field using `OutlinedTextField` (no border, just bottom divider or integrated into app bar) focused by default.
    - **State B (Typing):** Filters the list below.
    - **State C (Chips):** Selected recipients appear as `InputChip`s within the field.
3.  **Action List (Top of List):**
    - **"Create group":** A distinct `ListItem` with a group icon. Tapping this switches the screen to "Group Selection Mode" (or navigates to a dedicated state).
4.  **Contact List:**
    - **Section Headers:** "Recent", "All Contacts" (M3 Typography `LabelMedium`).
    - **Items:** Standard M3 `ListItem`.
      - **Leading:** `Avatar` (M3 Shape).
      - **Headline:** Contact Name.
      - **Supporting:** Number/Email & Service Type (iMessage/SMS).

**Interactions:**

- **Tap Contact:** Immediately opens 1:1 chat (if no others selected).
- **Tap "Create group":** Enters multi-select mode.

### B. Group Selection Mode

_Refined state within New Conversation or `GroupCreatorScreen`_

**Layout:**

1.  **Top App Bar:** Title changes to "New group".
2.  **Recipient Field:** Auto-focuses. Adding a person adds an `InputChip`.
3.  **List Items:**
    - Show **Checkboxes** (`Checkbox`) on the trailing edge to indicate multi-select state clearly.
4.  **Floating Action Button:**
    - `ExtendedFloatingActionButton` labeled "Next" appears when 2+ recipients are selected.

### C. Group Setup Screen

_Refined `GroupSetupScreen`_

**Layout:**

1.  **Header:** Large centered `Box` for Group Avatar.
    - Use `Surface` with `Shape.Large`.
    - "Edit" badge/icon overlay.
2.  **Group Name:**
    - `OutlinedTextField` with label "Group name".
    - Supporting text explaining visibility (iMessage vs MMS).
3.  **Participants List:**
    - Grouped list of selected members.
    - Trailing icon to remove.

## 4. Material Design 3 Component Mapping

| UI Element    | Current Implementation | M3 Replacement                                    |
| :------------ | :--------------------- | :------------------------------------------------ |
| **Top Bar**   | Custom / M2 TopAppBar  | `TopAppBar` / `CenterAlignedTopAppBar`            |
| **Search**    | Custom Surface         | `SearchBar` or `OutlinedTextField` (customized)   |
| **Chips**     | Custom Chips           | `InputChip`                                       |
| **List Item** | Custom Row             | `ListItem`                                        |
| **FAB**       | Extended FAB           | `ExtendedFloatingActionButton`                    |
| **Dialogs**   | Custom                 | `AlertDialog` (M3)                                |
| **Icons**     | Vector Assets          | `Icons.Outlined` / `Icons.Filled` (Auto-mirrored) |

## 5. Implementation Plan

### Phase 1: Component Modernization

- [ ] Create `M3ContactListItem` using `ListItem`.
- [ ] Create `M3RecipientField` using `BasicTextField` with `InputChip` support.
- [ ] Update `ChatCreatorScreen` to use `Scaffold` with M3 slots.

### Phase 2: Flow Refactor

- [ ] Add explicit "Create Group" row at the top of the contact list.
- [ ] Implement "Selection Mode" state in `ChatCreatorViewModel`.
- [ ] Deprecate `GroupCreatorScreen` (merge logic into `ChatCreatorScreen` or keep as a specialized view of the same ViewModel).

### Phase 3: Visual Polish

- [ ] Apply Dynamic Color (Material You) to backgrounds and highlights.
- [ ] Add shared element transitions for the "Next" -> "Group Setup" flow.
- [ ] Implement predictive back gesture support.

## 6. Accessibility Improvements

- Ensure minimum touch target size (48dp) for all list items and chips.
- Add proper `contentDescription` for avatars and action buttons.
- Support Dynamic Type scaling.
