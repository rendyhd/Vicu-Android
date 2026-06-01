# Task Notes Indicator — Desktop-Parity Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android "has notes" row indicator fire only when a task description has *real* content, matching desktop `hasNotesContent` (empty HTML like `<p></p>` / `<p>&nbsp;</p>` must NOT show the icon).

**Architecture:** The notes icon and `TaskLinkParser.hasNotesContent()` already exist and are committed (`8ad9a66`). The only defect: the Android helper strips note/page *links* but not residual HTML tags or `&nbsp;`/whitespace, so empty-HTML descriptions wrongly return `true`. We tighten the one helper and lock it with a unit test. Pure-logic change, no UI edits.

**Tech Stack:** Kotlin, JUnit (unit tests run under `app/src/test/java`, same as existing `TaskLinkParserTest`).

---

## Background (verified facts)

- Desktop reference — `C:\Users\rendy\vscode\vicu\src\renderer\lib\note-link.ts:77-82`:
  ```ts
  export function hasNotesContent(description: string | undefined | null): boolean {
    if (!description) return false
    const stripped = stripPageLink(stripNoteLink(description))
    return stripped.replace(/<[^>]+>/g, '').replace(/\s|&nbsp;/g, '').length > 0
  }
  ```
- Android current — `app/src/main/java/com/rendyhd/vicu/util/TaskLinkParser.kt:63-66`:
  ```kotlin
  fun hasNotesContent(description: String?): Boolean {
      if (description.isNullOrBlank()) return false
      return stripLinks(description).isNotBlank()
  }
  ```
  `stripLinks()` removes only the note/page link comments + anchors — it leaves generic tags and `&nbsp;`. So `"<p></p>"` returns `true` on Android but `false` on desktop.
- The icon render site (`TaskItem.kt:125-132`) already calls `TaskLinkParser.hasNotesContent(task.description)` and needs **no change**.
- `Task.description` is a non-null `String` (default `""`), HTML content.

## File Structure

- Modify: `app/src/main/java/com/rendyhd/vicu/util/TaskLinkParser.kt` — tighten `hasNotesContent`.
- Create: `app/src/test/java/com/rendyhd/vicu/util/TaskLinkParserNotesContentTest.kt` — unit test for the helper.

---

### Task 1: Lock desktop-parity behavior with a failing test

**Files:**
- Test: `app/src/test/java/com/rendyhd/vicu/util/TaskLinkParserNotesContentTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/rendyhd/vicu/util/TaskLinkParserNotesContentTest.kt`:

```kotlin
package com.rendyhd.vicu.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLinkParserNotesContentTest {

    @Test
    fun `blank or null description has no notes`() {
        assertFalse(TaskLinkParser.hasNotesContent(null))
        assertFalse(TaskLinkParser.hasNotesContent(""))
        assertFalse(TaskLinkParser.hasNotesContent("   "))
    }

    @Test
    fun `empty html has no notes`() {
        assertFalse(TaskLinkParser.hasNotesContent("<p></p>"))
        assertFalse(TaskLinkParser.hasNotesContent("<p><br></p>"))
        assertFalse(TaskLinkParser.hasNotesContent("<p>&nbsp;</p>"))
        assertFalse(TaskLinkParser.hasNotesContent("<div>\n  \n</div>"))
    }

    @Test
    fun `real text content has notes`() {
        assertTrue(TaskLinkParser.hasNotesContent("<p>Buy milk</p>"))
        assertTrue(TaskLinkParser.hasNotesContent("plain text"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.TaskLinkParserNotesContentTest"`
Expected: FAIL on `empty html has no notes` (current helper returns `true` for `<p></p>` etc.).

- [ ] **Step 3: Tighten the helper**

In `app/src/main/java/com/rendyhd/vicu/util/TaskLinkParser.kt`, replace the body of `hasNotesContent`:

```kotlin
fun hasNotesContent(description: String?): Boolean {
    if (description.isNullOrBlank()) return false
    val stripped = stripLinks(description)
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s|&nbsp;"), "")
    return stripped.isNotEmpty()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.rendyhd.vicu.util.TaskLinkParserNotesContentTest"`
Expected: PASS (all three test methods).

- [ ] **Step 5: Run the full unit-test suite to confirm no regressions**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL (existing `TaskLinkParserTest`, `TaskSortTest`, `RefreshBackoffTest` still pass).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/util/TaskLinkParser.kt app/src/test/java/com/rendyhd/vicu/util/TaskLinkParserNotesContentTest.kt
git commit -m "Fix notes indicator: ignore empty-HTML descriptions (desktop parity)"
```

---

## Self-Review

- **Spec coverage:** The one desktop behavior (empty HTML → no icon) is covered by Task 1, Step 3 and tested in Step 1.
- **No placeholders:** All code is concrete.
- **Type consistency:** `hasNotesContent(description: String?)` signature unchanged; callers in `TaskItem.kt` unaffected.
