# Signing the Mac app

macOS remembers Accessibility and Screen Recording against an app's code
signature. An ad-hoc signed `Andee.app` is a new app on every build, so every
grant lapses after each rebuild (the switch in System Settings still shows *on*
for the old build). Signing with one fixed identity keeps grants across builds.

Only the person who **packages** Andee needs this. Anyone who just uses the
built `Andee.app` needs nothing.

## What is set up

- `~/Andee-signing/andee.p12` — a self-signed code-signing certificate,
  "Andee Local Signing" (10 years). **Back this file up**: a new certificate is
  a new app to macOS, and every user would have to grant permissions again.
- `~/Library/Keychains/andee-signing.keychain-db` — a keychain holding only
  that identity, unlocked by `~/Andee-signing/keychain-password`, so builds
  sign without a password prompt.
- `./gradlew :desktop:createDistributable` runs `signApp` after packaging. With
  no such keychain it skips signing and the app stays ad-hoc.

Override with `-Pandee.signing.identity=…` / `-Pandee.signing.keychain=…`
(e.g. a real "Developer ID Application" identity later).

## On a new packaging machine

```sh
mkdir -p ~/Andee-signing && cp andee.p12 ~/Andee-signing/   # the backed-up file
cd ~/Andee-signing
PW=$(openssl rand -hex 16); echo -n "$PW" > keychain-password; chmod 600 keychain-password
KC=~/Library/Keychains/andee-signing.keychain-db
security create-keychain -p "$PW" $KC
security set-keychain-settings $KC
security unlock-keychain -p "$PW" $KC
security import andee.p12 -k $KC -P andee -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$PW" $KC
security list-keychains -d user -s $(security list-keychains -d user | tr -d '"') $KC
```

## What users see

The certificate is not from Apple, so Gatekeeper refuses the first launch of a
downloaded copy.

**On macOS 15 (Sequoia) and later, "right-click → Open" does not work any more** —
Apple removed that override, and it is still what most of the internet says, so
expect users to have been told the wrong thing. The current path is:

1. Double-click Andee; it is refused. Dismiss the dialog.
2. System Settings → Privacy & Security → scroll to **Security**.
3. A line names Andee as blocked, with an **Open Anyway** button beside it.
4. Confirm, and authenticate.

⚠️ The button only appears for **about an hour** after the failed launch attempt.
If it is missing, launch Andee again and go straight back to Settings — this is
the single most common reason someone reports "there is no such button".

Once done, and after every update signed with the same certificate, Accessibility
and Screen Recording stay granted.

Removing that step needs an Apple Developer ID ($99/year) and
notarization; pass its identity with `-Pandee.signing.identity`.

### Do not tell users to run `xattr -d com.apple.quarantine`

It comes up in every forum thread and it does make the dialog go away — by
removing the flag that records the file came from the internet, for *any* file,
including one that should have been blocked. It teaches a habit that defeats the
check everywhere. The Settings path costs one extra step and keeps the mechanism
that asks about the next app.
