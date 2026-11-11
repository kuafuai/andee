Native helpers for the Mac app, compiled with the Command Line Tools' `swiftc`
(no Xcode; CLT 15+, since the bridge needs the macOS 14 SDK). The build compiles them into `src/main/resources` via the
`buildNative` task; the outputs are not committed.

- `trash.swift` → `andee-trash`: moves its arguments to the Trash with
  `FileManager.trashItem`, so a command's `rm` can be undone.
