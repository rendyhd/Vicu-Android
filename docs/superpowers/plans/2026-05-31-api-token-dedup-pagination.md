# API Token Dedup — Pagination Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Android's duplicate-API-token cleanup robust when a user has many tokens, by paginating the `GET /tokens` sweep the way desktop does (up to 10 pages of 100), instead of trusting a single default page.

**Architecture:** The duplicate-token fix (device-scoped title + fire-and-forget sibling cleanup) is **already implemented and committed** (`8ad9a66`) in `AuthManager.cleanupSiblingTokens`. The only remaining gap vs desktop: Android's `listApiTokens()` issues one un-paginated `GET /tokens`, so siblings beyond page 1 can be missed. We add pagination to the Retrofit call and have the cleanup sweep all pages.

**Tech Stack:** Kotlin, Retrofit, Hilt, kotlinx coroutines.

---

## Background (verified facts)

- Desktop reference — `C:\Users\rendy\vscode\vicu\src\main\auth\oidc-login.ts` `listAPITokens` (≈318-337): pages `GET /api/v1/tokens?page=N&per_page=100`, up to 10 pages, deliberately does NOT early-break on a short page (Vikunja may cap `per_page`).
- Android current — `app/src/main/java/com/rendyhd/vicu/data/remote/api/VikunjaApiService.kt:141-148`:
  ```kotlin
  @PUT("tokens")    suspend fun createApiToken(@Body body: ApiTokenRequestDto): ApiTokenResponseDto
  @GET("tokens")    suspend fun listApiTokens(): List<ApiTokenDto>
  @DELETE("tokens/{id}") suspend fun deleteApiToken(@Path("id") id: Long)
  ```
- Android cleanup — `app/src/main/java/com/rendyhd/vicu/auth/AuthManager.kt:577-594` `cleanupSiblingTokens(title, newTokenId)` calls `listApiTokens()` once, filters `it.title == title && it.id != newTokenId`, deletes each.
- OpenAPI (`docs.json`): `GET /tokens` accepts query params `page` (int), `per_page` (int), `s` (string); `models.APIToken` has `id`, `title`.
- `ApiTokenDto` (`AuthDtos.kt`) has `id: Long, title: String` — sufficient.

## File Structure

- Modify: `app/src/main/java/com/rendyhd/vicu/data/remote/api/VikunjaApiService.kt` — add `page`/`per_page` query params to `listApiTokens`.
- Modify: `app/src/main/java/com/rendyhd/vicu/auth/AuthManager.kt` — page the sweep inside `cleanupSiblingTokens`.

> Note: token cleanup runs on `appScope` (fire-and-forget) and is not easily unit-testable without a fake API + coroutine test harness, which this module does not currently have. Verification is by compile + a manual smoke check. Keep the change minimal and mechanical.

---

### Task 1: Add pagination params to the list endpoint

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/data/remote/api/VikunjaApiService.kt:142`

- [ ] **Step 1: Add query params to `listApiTokens`**

Replace:

```kotlin
@GET("tokens")
suspend fun listApiTokens(): List<ApiTokenDto>
```

with:

```kotlin
@GET("tokens")
suspend fun listApiTokens(
    @Query("page") page: Int = 1,
    @Query("per_page") perPage: Int = 100,
): List<ApiTokenDto>
```

Confirm `import retrofit2.http.Query` is present at the top of the file (it is used by other endpoints such as `getAllTasks`); if not, add it.

- [ ] **Step 2: Compile to verify the signature change is consistent**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (The default args keep any other caller compiling; the only caller is `AuthManager`, updated next.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/data/remote/api/VikunjaApiService.kt
git commit -m "Add pagination params to listApiTokens endpoint"
```

---

### Task 2: Sweep all pages during sibling cleanup

**Files:**
- Modify: `app/src/main/java/com/rendyhd/vicu/auth/AuthManager.kt:577-594`

- [ ] **Step 1: Page the token list inside `cleanupSiblingTokens`**

Replace the existing single-call collection of siblings:

```kotlin
val siblings = apiServiceProvider.get().listApiTokens()
    .filter { it.title == title && it.id != newTokenId }
```

with a bounded multi-page sweep (mirrors desktop: up to 10 pages of 100, no early-break on short page):

```kotlin
val api = apiServiceProvider.get()
val allTokens = mutableListOf<com.rendyhd.vicu.data.remote.api.ApiTokenDto>()
val maxPages = 10
var page = 1
while (page <= maxPages) {
    val batch = api.listApiTokens(page = page, perPage = 100)
    if (batch.isEmpty()) break
    allTokens.addAll(batch)
    page++
}
val siblings = allTokens.filter { it.title == title && it.id != newTokenId }
```

Then keep the existing delete loop unchanged:

```kotlin
for (sibling in siblings) {
    try {
        api.deleteApiToken(sibling.id)
        AuthDebugLog.log("TOKEN_CLEANUP", "deleted sibling id=${sibling.id}")
    } catch (e: Exception) {
        AuthDebugLog.log("TOKEN_CLEANUP", "failed to delete sibling id=${sibling.id}: ${e.message}")
    }
}
```

> Note: `break` on an empty page is safe (an empty page means no more tokens). Desktop avoids early-break on a *short* page (e.g. 50 < 100) because Vikunja may cap `per_page` below the request; an empty page is the unambiguous terminator. The 10-page cap bounds the loop regardless.

- [ ] **Step 2: Compile to verify**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke check (optional but recommended)**

On a device/emulator signed into a Vikunja account, watch the auth debug log (Settings → Data & Sync → Auth debug log) after a fresh sign-in. Expected: `TOKEN_CLEANUP` entries delete old `"Vicu — <device>"` tokens, leaving exactly one per device. Confirm no `"Vicu — <device>"` duplicates remain via the Vikunja web UI (Settings → API Tokens).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rendyhd/vicu/auth/AuthManager.kt
git commit -m "Paginate sibling API-token cleanup to match desktop sweep"
```

---

## Self-Review

- **Spec coverage:** Desktop's paged sweep (10×100) is reproduced in Task 2, Step 1. Device-scoped title + fire-and-forget cleanup already exist (no task needed).
- **No placeholders:** All code concrete; the only manual step (Step 3, Task 2) is explicitly optional verification.
- **Type consistency:** `listApiTokens(page, perPage)` defaults keep the API compiling; `ApiTokenDto` fully-qualified in the new code matches its package `com.rendyhd.vicu.data.remote.api`.
