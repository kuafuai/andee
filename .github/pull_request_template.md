<!--
Keep this short. Delete the sections that genuinely do not apply, but do not delete a
section just to avoid answering it — the ones below exist because they have bitten us.
-->

## What and why

<!-- One paragraph. What changes, and what problem it solves. Link the issue it closes, if any. -->

Closes #

## Scope

<!-- List the files/areas this touches. If it touches something outside a single feature, say why. -->

-

## The three kinds of Chinese (only read this if you touched any string)

This repo separates text by **who reads it**, and the split is not cosmetic:

| Kind | Where it lives | Rule |
|---|---|---|
| The **user** reads it (windows, cards, bubbles, settings, errors) | `res/values/strings.xml` + `res/values-en/strings.xml`, reached **only** through `i18n/AppLocale` | Add to **both** tables. `context.getString` does not reach the overlay, so it will not work here. |
| The **model** reads it (prompts, tool schemas, tool results) | `brain/LocalPrompt.kt` — the single copy | Written in **English**. Do not add a second source. |
| **Matched against system text** (call keywords, unread-count regex, clipboard labels) | `NotificationRelayService`, `ScreenController` | **Never translate.** These compare against what WeChat/Android emit; translating breaks the feature. |

Literal on-screen text the model must read aloud (e.g. a settings path, a button label) and
"what a Chinese user would actually say" specimens stay in Chinese by design — see CONTRIBUTING.md.

- [ ] If I added a user-facing string, it is in **both** `values/` and `values-en/`.
- [ ] If I added model-facing prose, it is **English** and lives in `LocalPrompt.kt`.
- [ ] I did **not** translate anything in the "matched against system text" category.

## How it was verified

Build with **debug**, not release — the release variant has no signing config and produces an
unsigned APK that will not install.

```bash
./gradlew clean :app:assembleDebug
```

- [ ] `assembleDebug` succeeds.

**Device tested on:** <!-- model / Android version / ROM, e.g. HONOR AMM-AN00 · Android 15 · MagicOS. Say "not tested on a device" if you did not — that is fine, it just changes how this gets reviewed. -->

**What was actually observed:** <!-- Not "it works" — what you saw. Screen reading, a logcat line, a pixel measurement, a dump. -->

> If you verified by searching strings inside the built APK, do **not** use `grep` on the dex
> for non-ASCII text — it produces false negatives (a string that is present reports zero hits).
> Compare against the dex string table instead, and list what remains rather than what matched.

## Security and permissions

- [ ] This changes no permission, no `AndroidManifest.xml` entry, and nothing in the network layer.
- [ ] **Or:** it does, and the following is spelled out below — including whether it widens what an
      unauthenticated or local caller can do.

<!-- If the box above is checked, note here: new/changed permissions, any new network surface or
     bind address, and whether anything the user can read now leaves the device. -->

## Checklist

- [ ] I only changed files related to this PR. Untracked files left by other work are still
      untracked — I did not commit, stash, or delete them.
- [ ] Commit messages say what changed and why.
- [ ] I have read [CONTRIBUTING.md](https://github.com/kuafuai/andee/blob/main/CONTRIBUTING.md)
      ([中文](https://github.com/kuafuai/andee/blob/main/CONTRIBUTING.zh-CN.md))
      and agree to the contribution licensing terms in [LICENSE](https://github.com/kuafuai/andee/blob/main/LICENSE) §A.2.
