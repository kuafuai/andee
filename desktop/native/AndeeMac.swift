// Andee's native bridge on macOS: the things a JVM cannot do cleanly, as C
// functions the Kotlin side calls through JNA. Every function returns plain
// values or a malloc'd UTF-8 JSON string the caller frees with andee_free.
//
// Built with the Command Line Tools' swiftc (no Xcode):
//   swiftc -O -target arm64-apple-macos14 -emit-library AndeeMac.swift -o libandeemac.dylib
// macOS 14 is the floor: ScreenCaptureKit's one-shot screenshot arrived there.

import SQLite3
import AppKit
import ApplicationServices
import AVFoundation
import CoreLocation
import CoreGraphics
import Foundation
import ScreenCaptureKit

// MARK: - helpers

private func cstr(_ s: String) -> UnsafeMutablePointer<CChar> { strdup(s) }

private func json(_ o: Any) -> UnsafeMutablePointer<CChar> {
    let data = (try? JSONSerialization.data(withJSONObject: o, options: [])) ?? Data("{}".utf8)
    return cstr(String(data: data, encoding: .utf8) ?? "{}")
}

@_cdecl("andee_free")
public func andee_free(_ p: UnsafeMutablePointer<CChar>?) { free(p) }

// MARK: - permissions

@_cdecl("andee_ax_trusted")
public func andee_ax_trusted(_ prompt: Int32) -> Int32 {
    let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt != 0] as CFDictionary
    return AXIsProcessTrustedWithOptions(opts) ? 1 : 0
}

@_cdecl("andee_screen_capture_allowed")
public func andee_screen_capture_allowed(_ request: Int32) -> Int32 {
    if CGPreflightScreenCaptureAccess() { return 1 }
    if request != 0 { return CGRequestScreenCaptureAccess() ? 1 : 0 }
    return 0
}

// MARK: - display / apps

@_cdecl("andee_main_display")
public func andee_main_display() -> UnsafeMutablePointer<CChar> {
    let b = CGDisplayBounds(CGMainDisplayID())
    let scale = NSScreen.main?.backingScaleFactor ?? 2
    return json(["x": b.origin.x, "y": b.origin.y, "w": b.width, "h": b.height, "scale": scale])
}

/// The app the user is looking at, never Andee itself.
///
/// Asking our own process for its accessibility tree is not a question to another
/// app: AppKit answers it inside this process, on the calling thread, by calling
/// into AWT's Java accessibility — off the main thread, which kills the JVM
/// (SIGBUS in CAccessibility, twice). And it is the wrong answer anyway: when
/// Andee is frontmost it is because the user just touched the ball, and what they
/// mean by "the screen" is the app behind it. So: the frontmost app if it is not
/// us, else the owner of the frontmost ordinary window that is not ours.
private func targetApp() -> NSRunningApplication? {
    let me = getpid()
    if let f = NSWorkspace.shared.frontmostApplication, f.processIdentifier != me { return f }
    let opts: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
    if let list = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] {
        for w in list {
            guard (w[kCGWindowLayer as String] as? Int) == 0,
                  let pid = w[kCGWindowOwnerPID as String] as? pid_t, pid != me,
                  let app = NSRunningApplication(processIdentifier: pid),
                  app.activationPolicy == .regular else { continue }
            return app
        }
    }
    return nil
}

/// The focused element of the target app, asked of that app directly. The
/// system-wide "focused element" is Andee's own whenever Andee is frontmost.
private func targetFocused() -> AXUIElement? {
    guard let app = targetApp() else { return nil }
    let ax = AXUIElementCreateApplication(app.processIdentifier)
    guard let f = attr(ax, kAXFocusedUIElementAttribute) else { return nil }
    return (f as! AXUIElement)
}

/// The process whose window is frontmost at a point, skipping ours (at any alpha:
/// ours are faded, not gone, while a click is under way). Nil when nothing but
/// Andee is there.
private func otherAppAt(_ x: Double, _ y: Double) -> pid_t? {
    let me = getpid()
    guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID)
        as? [[String: Any]] else { return nil }
    for w in list {
        guard let pid = w[kCGWindowOwnerPID as String] as? pid_t, pid != me,
              (w[kCGWindowAlpha as String] as? Double ?? 1) > 0,
              let b = w[kCGWindowBounds as String] as? [String: Any],
              let r = CGRect(dictionaryRepresentation: b as CFDictionary),
              r.contains(CGPoint(x: x, y: y)) else { continue }
        return pid
    }
    return nil
}

/// Whether an element belongs to this process; such an element must not be queried (see targetApp).
private func isOurs(_ e: AXUIElement) -> Bool {
    var pid: pid_t = 0
    return AXUIElementGetPid(e, &pid) == .success && pid == getpid()
}

@_cdecl("andee_frontmost")
public func andee_frontmost() -> UnsafeMutablePointer<CChar> {
    guard let app = targetApp() else { return json([:]) }
    return json(["name": app.localizedName ?? "", "bundle": app.bundleIdentifier ?? "", "pid": Int(app.processIdentifier)])
}

@_cdecl("andee_running_apps")
public func andee_running_apps() -> UnsafeMutablePointer<CChar> {
    let apps = NSWorkspace.shared.runningApplications.filter { $0.activationPolicy == .regular }
    return json(apps.map { ["name": $0.localizedName ?? "", "bundle": $0.bundleIdentifier ?? "", "pid": Int($0.processIdentifier), "active": $0.isActive] })
}

// MARK: - accessibility tree

private func attr(_ e: AXUIElement, _ name: String) -> CFTypeRef? {
    var v: CFTypeRef?
    return AXUIElementCopyAttributeValue(e, name as CFString, &v) == .success ? v : nil
}

private func str(_ e: AXUIElement, _ name: String) -> String? {
    guard let v = attr(e, name) else { return nil }
    if let s = v as? String { return s.isEmpty ? nil : s }
    if let n = v as? NSNumber { return n.stringValue }
    return nil
}

private func frame(_ e: AXUIElement) -> CGRect? {
    guard let p = attr(e, kAXPositionAttribute), let s = attr(e, kAXSizeAttribute) else { return nil }
    var pt = CGPoint.zero, sz = CGSize.zero
    AXValueGetValue(p as! AXValue, .cgPoint, &pt)
    AXValueGetValue(s as! AXValue, .cgSize, &sz)
    return CGRect(origin: pt, size: sz)
}

private func actions(_ e: AXUIElement) -> [String] {
    var names: CFArray?
    guard AXUIElementCopyActionNames(e, &names) == .success, let a = names as? [String] else { return [] }
    return a
}

private var nodeBudget = 0

private func walk(_ e: AXUIElement, depth: Int) -> [String: Any]? {
    nodeBudget -= 1
    if nodeBudget < 0 || depth > 40 { return nil }
    var n: [String: Any] = [:]
    n["role"] = str(e, kAXRoleAttribute) ?? ""
    if let sub = str(e, kAXSubroleAttribute) { n["subrole"] = sub }
    if let t = str(e, kAXTitleAttribute) { n["title"] = t }
    if let d = str(e, kAXDescriptionAttribute) { n["desc"] = d }
    if let v = str(e, kAXValueAttribute) { n["value"] = String(v.prefix(200)) }
    if let h = str(e, kAXHelpAttribute) { n["help"] = String(h.prefix(120)) }
    if let id = str(e, kAXIdentifierAttribute) { n["id"] = id }
    if let p = str(e, kAXPlaceholderValueAttribute) { n["placeholder"] = p }
    if let f = frame(e) { n["frame"] = [f.origin.x, f.origin.y, f.width, f.height] }
    if let en = attr(e, kAXEnabledAttribute) as? Bool, !en { n["disabled"] = true }
    if let fo = attr(e, kAXFocusedAttribute) as? Bool, fo { n["focused"] = true }
    let acts = actions(e)
    if acts.contains(kAXPressAction as String) { n["press"] = true }
    if let settable = settableValue(e), settable { n["editable"] = true }
    if let kids = attr(e, kAXChildrenAttribute) as? [AXUIElement], !kids.isEmpty {
        let c = kids.compactMap { walk($0, depth: depth + 1) }
        if !c.isEmpty { n["children"] = c }
    }
    return n
}

private func settableValue(_ e: AXUIElement) -> Bool? {
    var s: DarwinBoolean = false
    return AXUIElementIsAttributeSettable(e, kAXValueAttribute as CFString, &s) == .success ? s.boolValue : nil
}

/** The frontmost app's focused window (or its first window) as a JSON tree. */
@_cdecl("andee_ax_tree")
public func andee_ax_tree(_ maxNodes: Int32) -> UnsafeMutablePointer<CChar> {
    guard let app = targetApp() else { return json(["error": "no app window on screen besides Andee"]) }
    let ax = AXUIElementCreateApplication(app.processIdentifier)
    // Chromium and Electron build their tree only when asked.
    AXUIElementSetAttributeValue(ax, "AXManualAccessibility" as CFString, kCFBooleanTrue)
    AXUIElementSetAttributeValue(ax, "AXEnhancedUserInterface" as CFString, kCFBooleanTrue)
    var win: AXUIElement? = nil
    if let w = attr(ax, kAXFocusedWindowAttribute) { win = (w as! AXUIElement) }
    else if let ws = attr(ax, kAXWindowsAttribute) as? [AXUIElement], let f = ws.first { win = f }
    nodeBudget = Int(maxNodes)
    var out: [String: Any] = ["app": app.localizedName ?? "", "bundle": app.bundleIdentifier ?? "", "pid": Int(app.processIdentifier)]
    if let w = win {
        out["window_title"] = str(w, kAXTitleAttribute) ?? ""
        out["tree"] = walk(w, depth: 0) ?? [:]
    } else {
        out["tree"] = [:]
        out["no_window"] = true
    }
    // The menu bar is part of the app a person can reach.
    if let mb = attr(ax, kAXMenuBarAttribute) {
        nodeBudget = 400
        out["menubar"] = walk(mb as! AXUIElement, depth: 0) ?? [:]
    }
    out["truncated"] = nodeBudget < 0
    return json(out)
}

/** Press the element at an index path from the last tree (child indices from the window root). */
@_cdecl("andee_ax_press_at")
public func andee_ax_press_at(_ x: Double, _ y: Double) -> Int32 {
    // Never the system-wide hit test: it reaches our own windows even while they are
    // faded out for this very click (alpha 0 still hit-tests), and asking our own
    // process kills the JVM. Ask the app that owns the point, by name.
    guard let pid = otherAppAt(x, y) else { return 0 }  // the caller falls back to a real click
    let app = AXUIElementCreateApplication(pid)
    var el: AXUIElement?
    guard AXUIElementCopyElementAtPosition(app, Float(x), Float(y), &el) == .success, let e = el, !isOurs(e) else { return 0 }
    return AXUIElementPerformAction(e, kAXPressAction as CFString) == .success ? 1 : 0
}

/** Set the focused element's value (a text field), as a person pasting it would. */
@_cdecl("andee_ax_set_focused_value")
public func andee_ax_set_focused_value(_ text: UnsafePointer<CChar>) -> Int32 {
    guard let e = targetFocused(), !isOurs(e) else { return 0 }
    return AXUIElementSetAttributeValue(e, kAXValueAttribute as CFString, String(cString: text) as CFString) == .success ? 1 : 0
}

@_cdecl("andee_ax_focused_value")
public func andee_ax_focused_value() -> UnsafeMutablePointer<CChar> {
    guard let e = targetFocused(), !isOurs(e) else { return json(["focused": false]) }
    return json(["focused": true, "role": str(e, kAXRoleAttribute) ?? "", "value": str(e, kAXValueAttribute) ?? ""])
}

// MARK: - input events (points in global display coordinates, top-left origin)

private func post(_ e: CGEvent?) { e?.post(tap: .cghidEventTap) }

@_cdecl("andee_click")
public func andee_click(_ x: Double, _ y: Double, _ button: Int32, _ count: Int32, _ holdMs: Int32) {
    let p = CGPoint(x: x, y: y)
    let (down, up, b): (CGEventType, CGEventType, CGMouseButton) =
        button == 1 ? (.rightMouseDown, .rightMouseUp, .right) : (.leftMouseDown, .leftMouseUp, .left)
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: b))
    usleep(30_000)
    for i in 1...max(1, Int(count)) {
        let d = CGEvent(mouseEventSource: nil, mouseType: down, mouseCursorPosition: p, mouseButton: b)
        d?.setIntegerValueField(.mouseEventClickState, value: Int64(i))
        post(d)
        usleep(useconds_t(max(30, Int(holdMs)) * 1000))
        let u = CGEvent(mouseEventSource: nil, mouseType: up, mouseCursorPosition: p, mouseButton: b)
        u?.setIntegerValueField(.mouseEventClickState, value: Int64(i))
        post(u)
        usleep(60_000)
    }
}

@_cdecl("andee_drag")
public func andee_drag(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double, _ ms: Int32) {
    let steps = max(8, Int(ms) / 16)
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: CGPoint(x: x1, y: y1), mouseButton: .left))
    for i in 1...steps {
        let t = Double(i) / Double(steps)
        let p = CGPoint(x: x1 + (x2 - x1) * t, y: y1 + (y2 - y1) * t)
        post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseDragged, mouseCursorPosition: p, mouseButton: .left))
        usleep(useconds_t(max(1, Int(ms) / steps) * 1000))
    }
    usleep(120_000)
    post(CGEvent(mouseEventSource: nil, mouseType: .leftMouseUp, mouseCursorPosition: CGPoint(x: x2, y: y2), mouseButton: .left))
}

/** Scroll by [dy]/[dx] pixels at a point, in small steps so lists move as far as asked and no further. */
@_cdecl("andee_scroll")
public func andee_scroll(_ x: Double, _ y: Double, _ dx: Int32, _ dy: Int32) {
    post(CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: CGPoint(x: x, y: y), mouseButton: .left))
    usleep(30_000)
    let steps = 10
    for _ in 0..<steps {
        let e = CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                        wheel1: dy / Int32(steps), wheel2: dx / Int32(steps), wheel3: 0)
        post(e)
        usleep(16_000)
    }
}

/** Type text as keystrokes carrying Unicode, which works in any field regardless of keyboard layout. */
@_cdecl("andee_type_text")
public func andee_type_text(_ text: UnsafePointer<CChar>) {
    let s = Array(String(cString: text).utf16)
    var i = 0
    while i < s.count {
        let chunk = Array(s[i..<min(i + 16, s.count)])
        let d = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true)
        chunk.withUnsafeBufferPointer { d?.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: $0.baseAddress) }
        post(d)
        let u = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false)
        post(u)
        usleep(8_000)
        i += 16
    }
}

/**
 * Put [text] on the general pasteboard. The caller then sends Cmd+V, which is a real paste event.
 *
 * Why this exists: setting a field's AXValue changes what the control *shows* and tells the page nothing.
 * Web and Electron editors (X, WorkBuddy) keep their own state and update it from `input` events, so a field
 * filled through AXValue reads as empty to the app and its send button stays dead, while the screen plainly
 * shows the text. A paste arrives as the event the editor listens for. The pasteboard is overwritten, not
 * restored: that was the choice made, and restoring races a user who copies something in the same moment.
 */
@_cdecl("andee_set_pasteboard")
public func andee_set_pasteboard(_ text: UnsafePointer<CChar>) -> Int32 {
    let s = String(cString: text)
    var ok: Int32 = 0
    let run = {
        let pb = NSPasteboard.general
        pb.clearContents()
        ok = pb.setString(s, forType: .string) ? 1 : 0
    }
    if Thread.isMainThread { run() } else { DispatchQueue.main.sync(execute: run) }
    return ok
}

/** A key with modifiers: flags bit 0 cmd, 1 shift, 2 option, 3 control. */
@_cdecl("andee_key")
public func andee_key(_ keyCode: Int32, _ flags: Int32) {
    var f: CGEventFlags = []
    if flags & 1 != 0 { f.insert(.maskCommand) }
    if flags & 2 != 0 { f.insert(.maskShift) }
    if flags & 4 != 0 { f.insert(.maskAlternate) }
    if flags & 8 != 0 { f.insert(.maskControl) }
    let d = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(keyCode), keyDown: true)
    d?.flags = f
    post(d)
    let u = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(keyCode), keyDown: false)
    u?.flags = f
    post(u)
}

// MARK: - screenshots (ScreenCaptureKit, our own windows excluded)

/**
 * Capture the main display to a PNG at [path], leaving out every window of
 * this process — the ball and the cards are never in the picture.
 * Returns 1 on success.
 */
@_cdecl("andee_screenshot")
public func andee_screenshot(_ path: UnsafePointer<CChar>, _ maxSide: Int32) -> Int32 {
    let out = URL(fileURLWithPath: String(cString: path))
    let sem = DispatchSemaphore(value: 0)
    final class Box: @unchecked Sendable { var ok: Int32 = 0 }
    let box = Box()
    Task {
        defer { sem.signal() }
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let display = content.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content.displays.first else { return }
            let mine = content.applications.filter { $0.processID == getpid() }
            let filter = SCContentFilter(display: display, excludingApplications: mine, exceptingWindows: [])
            let cfg = SCStreamConfiguration()
            let scale = NSScreen.main?.backingScaleFactor ?? 2
            var w = Double(display.width) * scale, h = Double(display.height) * scale
            let longSide = max(w, h)
            if maxSide > 0 && longSide > Double(maxSide) { let k = Double(maxSide) / longSide; w *= k; h *= k }
            cfg.width = Int(w); cfg.height = Int(h)
            cfg.showsCursor = false
            let img = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: cfg)
            let rep = NSBitmapImageRep(cgImage: img)
            if let png = rep.representation(using: .png, properties: [:]) { try png.write(to: out); box.ok = 1 }
        } catch {
            FileHandle.standardError.write("screenshot: \(error)\n".data(using: .utf8)!)
        }
    }
    _ = sem.wait(timeout: .now() + 10)
    return box.ok
}

// MARK: - workspace

@_cdecl("andee_open")
public func andee_open(_ target: UnsafePointer<CChar>) -> Int32 {
    let t = String(cString: target)
    if let u = URL(string: t), u.scheme != nil { return NSWorkspace.shared.open(u) ? 1 : 0 }
    if let u = NSWorkspace.shared.urlForApplication(withBundleIdentifier: t) {
        let sem = DispatchSemaphore(value: 0); var ok: Int32 = 0
        NSWorkspace.shared.openApplication(at: u, configuration: NSWorkspace.OpenConfiguration()) { app, _ in ok = app != nil ? 1 : 0; sem.signal() }
        _ = sem.wait(timeout: .now() + 10); return ok
    }
    return NSWorkspace.shared.open(URL(fileURLWithPath: t)) ? 1 : 0
}

// MARK: - the floating ball's window

/// Lifts our own window titled [title] above everything, the phone's
/// TYPE_APPLICATION_OVERLAY: above normal and floating windows, on every Space,
/// and over other apps' full-screen Spaces. A JVM window can only ask for
/// "always on top", which is the floating level and stays on one Space.
@_cdecl("andee_float_window")
public func andee_float_window(_ title: UnsafePointer<CChar>) -> Int32 {
    let t = String(cString: title)
    // Async on purpose: AWT's main thread may itself be waiting on the JVM's
    // event thread, and a sync hop from there would deadlock the app.
    DispatchQueue.main.async {
        guard let w = NSApp?.windows.first(where: { $0.title == t }) else { return }
        w.level = .statusBar
        w.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        w.hidesOnDeactivate = false
        w.hasShadow = false
    }
    return NSApp == nil ? 0 : 1
}

/// Fade every window of ours in or out, for the instant of a click aimed at
/// another app. Native because AWT refuses `setOpacity` on a decorated window
/// (the chat), and a refusal half-way left the ball invisible for good.
/// Waits for the main thread at most half a second, never forever: the main
/// thread can itself be waiting on the JVM.
@_cdecl("andee_set_own_alpha")
public func andee_set_own_alpha(_ alpha: Double) -> Int32 {
    let done = DispatchSemaphore(value: 0)
    DispatchQueue.main.async {
        NSApp?.windows.forEach { $0.alphaValue = CGFloat(alpha) }
        done.signal()
    }
    return done.wait(timeout: .now() + 0.5) == .success ? 1 : 0
}

// MARK: - notifications (the system's own delivered-notification store)

/// macOS has no notification-listener API for third-party apps, but every
/// notification any app posts is written by `usernoted` into one SQLite file.
/// Watching that file's directory tells us within moments that something new
/// arrived, whether or not a banner was shown (Focus and per-app banner
/// settings do not stop the write). Read-only; never touches the file.
///
/// **Where that file is depends on the macOS version**, and assuming one place
/// is how a Mac ended up "without permission" forever: the store was not there,
/// the failed read was reported as a missing Full Disk Access grant, and granting
/// it changed nothing. macOS 15 keeps it in the usernoted group container; earlier
/// systems keep it under the per-user Darwin directory
/// (`confstr(_CS_DARWIN_USER_DIR)`, i.e. `/var/folders/…/0/`). Asked of the system
/// rather than derived from `$TMPDIR`, which the JVM replaces with its own.
private func notifCandidates() -> [String] {
    var out = [(NSHomeDirectory() as NSString)
        .appendingPathComponent("Library/Group Containers/group.com.apple.usernoted/db2")]
    var buf = [CChar](repeating: 0, count: Int(PATH_MAX))
    if confstr(_CS_DARWIN_USER_DIR, &buf, buf.count) > 0 {
        out.append((String(cString: buf) as NSString).appendingPathComponent("com.apple.notificationcenter/db2"))
    }
    return out
}

/// Where the store is: a readable directory, nowhere we know of, or somewhere we are not allowed to read.
private enum NotifStore { case at(String), missing, denied }

/// First candidate whose `db` opens. A path that exists but refuses us (EPERM/EACCES — the TCC answer) is
/// "denied"; only when no candidate exists at all is it "missing" — two different fixes, so two answers.
private func notifLocate() -> NotifStore {
    var denied = false
    for dir in notifCandidates() {
        let fd = open(dir + "/db", O_RDONLY)
        if fd >= 0 { close(fd); return .at(dir) }
        if errno == EPERM || errno == EACCES { denied = true }
    }
    return denied ? .denied : .missing
}

/// One query, in process, through the system's own SQLite, read-only. `usernoted` is writing to the same
/// file (its WAL is where new rows land first), so a short busy timeout rather than failing on its lock.
/// Nil when it cannot be opened or the statement fails.
private func notifQuery(_ dir: String, _ sql: String) -> [[String: Any]]? {
    var db: OpaquePointer?
    guard sqlite3_open_v2(dir + "/db", &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK, let db = db else {
        sqlite3_close(db)
        return nil
    }
    defer { sqlite3_close(db) }
    sqlite3_busy_timeout(db, 1000)
    var st: OpaquePointer?
    guard sqlite3_prepare_v2(db, sql, -1, &st, nil) == SQLITE_OK, let st = st else { return nil }
    defer { sqlite3_finalize(st) }
    var rows: [[String: Any]] = []
    while true {
        let rc = sqlite3_step(st)
        if rc == SQLITE_DONE { break }
        if rc != SQLITE_ROW { return nil }
        var row: [String: Any] = [:]
        for i in 0..<sqlite3_column_count(st) {
            let name = String(cString: sqlite3_column_name(st, i))
            switch sqlite3_column_type(st, i) {
            case SQLITE_INTEGER: row[name] = NSNumber(value: sqlite3_column_int64(st, i))
            case SQLITE_FLOAT: row[name] = NSNumber(value: sqlite3_column_double(st, i))
            case SQLITE_TEXT: row[name] = String(cString: sqlite3_column_text(st, i))
            case SQLITE_BLOB:
                let n = Int(sqlite3_column_bytes(st, i))
                if let p = sqlite3_column_blob(st, i) { row[name] = Data(bytes: p, count: n) }
            default: break
            }
        }
        rows.append(row)
    }
    return rows
}

/// Can the store be read at all: the newest record id, -1 when it exists and refuses us (Full Disk Access),
/// -2 when there is no store at any place this Mac's macOS is known to keep it.
@_cdecl("andee_notif_latest_id")
public func andee_notif_latest_id() -> Int64 {
    switch notifLocate() {
    case .missing: return -2
    case .denied: return -1
    case .at(let dir):
        guard let rows = notifQuery(dir, "select coalesce(max(rec_id),0) m from record"),
              let m = (rows.first?["m"] as? NSNumber)?.int64Value else { return -1 }
        return m
    }
}

/// Every record newer than [afterId], decoded: id, app bundle id, app name, title, subtitle, body, delivered (epoch ms).
@_cdecl("andee_notif_since")
public func andee_notif_since(_ afterId: Int64) -> UnsafeMutablePointer<CChar> {
    let sql = "select r.rec_id id, a.identifier bundle, r.delivered_date d, r.data data from record r join app a using(app_id) where r.rec_id > \(afterId) order by r.rec_id limit 50"
    guard case .at(let dir) = notifLocate(), let rows = notifQuery(dir, sql) else { return json(["error": "unreadable"]) }
    var out: [[String: Any]] = []
    for r in rows {
        let id = (r["id"] as? NSNumber)?.int64Value ?? 0
        let bundle = (r["bundle"] as? String) ?? ""
        var o: [String: Any] = ["id": id, "bundle": bundle]
        if let d = (r["d"] as? NSNumber)?.doubleValue { o["when"] = Int64((d + 978_307_200) * 1000) }
        if let blob = r["data"] as? Data,
           let pl = try? PropertyListSerialization.propertyList(from: blob, format: nil) as? [String: Any],
           let req = pl["req"] as? [String: Any] {
            o["title"] = req["titl"] as? String ?? ""
            o["subtitle"] = req["subt"] as? String ?? ""
            o["body"] = req["body"] as? String ?? ""
        }
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundle) {
            o["app"] = FileManager.default.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
        }
        out.append(o)
    }
    return json(["items": out])
}

public typealias NotifCallback = @convention(c) () -> Void

/// Call [cb] (on a background queue) whenever the store's directory or its WAL
/// changes. The WAL is where new rows land first; the directory catches the WAL
/// being created or replaced. Returns 0 when the directory cannot be opened.
@_cdecl("andee_notif_watch")
public func andee_notif_watch(_ cb: NotifCallback) -> Int32 {
    notifKeep.forEach { $0.cancel() }
    let q = DispatchQueue(label: "andee.notif")
    var sources: [DispatchSourceFileSystemObject] = []
    guard case .at(let notifDir) = notifLocate() else { notifKeep = []; return 0 }
    for path in [notifDir, notifDir + "/db-wal", notifDir + "/db"] {
        let fd = open(path, O_EVTONLY)
        if fd < 0 { continue }
        let s = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: [.write, .extend, .rename, .delete], queue: q)
        s.setEventHandler { cb() }
        s.setCancelHandler { close(fd) }
        s.resume()
        sources.append(s)
    }
    notifKeep = sources
    return sources.isEmpty ? 0 : 1
}
private var notifKeep: [DispatchSourceFileSystemObject] = []

/// Whether a visible window of ours covers this point — only then does a click need us out of the way.
@_cdecl("andee_own_window_at")
public func andee_own_window_at(_ x: Double, _ y: Double) -> Int32 {
    let me = getpid()
    guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]] else { return 1 }
    for w in list where (w[kCGWindowOwnerPID as String] as? pid_t) == me {
        if (w[kCGWindowAlpha as String] as? Double ?? 1) == 0 { continue }
        if let b = w[kCGWindowBounds as String] as? [String: Any],
           let r = CGRect(dictionaryRepresentation: b as CFDictionary),
           r.insetBy(dx: -4, dy: -4).contains(CGPoint(x: x, y: y)) { return 1 }
    }
    return 0
}

// MARK: - camera (device.look: one frame through the lens)

private final class PhotoTaker: NSObject, AVCapturePhotoCaptureDelegate {
    let done = DispatchSemaphore(value: 0)
    var jpeg: Data?
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        jpeg = photo.fileDataRepresentation()
        done.signal()
    }
}

/// Capture one frame from the camera to [path] as PNG, downscaled to [maxSide] on the long edge.
/// The Mac's honest analog of the phone's look session: one frame per call, no
/// persistent viewfinder. Returns 1 on success; 0 when there is no camera or
/// permission is missing (Camera usage is in the entitlements/Info.plist).
@_cdecl("andee_camera_shot")
public func andee_camera_shot(_ path: UnsafePointer<CChar>, _ front: Int32, _ maxSide: Int32) -> Int32 {
    let discovery = AVCaptureDevice.DiscoverySession(
        deviceTypes: [.builtInWideAngleCamera],
        mediaType: .video,
        position: front != 0 ? .front : .back
    )
    guard let device = discovery.devices.first(where: { front != 0 ? $0.position == .front : $0.position == .back })
        ?? discovery.devices.first ?? AVCaptureDevice.default(for: .video) else { return 0 }
    let session = AVCaptureSession()
    session.sessionPreset = .high
    guard let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return 0 }
    session.addInput(input)
    let output = AVCapturePhotoOutput()
    guard session.canAddOutput(output) else { return 0 }
    session.addOutput(output)
    let taker = PhotoTaker()
    session.startRunning()
    // Give the exposure a moment to settle (the phone's EXPOSURE_SETTLE_MS, for
    // the same reason: the first frame after start is washed out or near-black).
    usleep(400_000)
    output.capturePhoto(with: AVCapturePhotoSettings(), delegate: taker)
    let got = taker.done.wait(timeout: .now() + 5) == .success
    session.stopRunning()
    guard got, var data = taker.jpeg else { return 0 }
    let out = URL(fileURLWithPath: String(cString: path))
    if maxSide > 0, let src = CGImageSourceCreateWithData(data as CFData, nil),
       let img = CGImageSourceCreateImageAtIndex(src, 0, nil) {
        let w = img.width, h = img.height
        let long = max(w, h)
        let scale = min(1.0, Double(maxSide) / Double(long))
        let tw = max(1, Int(Double(w) * scale)), th = max(1, Int(Double(h) * scale))
        if let cs = CGColorSpace(name: CGColorSpace.sRGB),
           let ctx = CGContext(data: nil, width: tw, height: th, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                               bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) {
            ctx.interpolationQuality = .high
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: tw, height: th))
            if let small = ctx.makeImage(),
               let j = CFDataCreateMutable(nil, 0) {
                let dest = CGImageDestinationCreateWithData(j, "public.png" as CFString, 1, nil)!
                CGImageDestinationAddImage(dest, small, nil)
                if CGImageDestinationFinalize(dest) { data = j as Data }
            }
        }
    }
    return (try? data.write(to: out)) != nil ? 1 : 0
}

// MARK: - brightness & location (device.brightness / device.location)

@_silgen_name("CoreDisplay_Display_GetUserBrightness")
private func cdGetBrightness(_ d: CGDirectDisplayID) -> Double
@_silgen_name("CoreDisplay_Display_SetUserBrightness")
private func cdSetBrightness(_ d: CGDirectDisplayID, _ b: Double)

/// The main display's brightness, 0–1. CoreDisplay works on the built-in panel; -1 when it cannot.
@_cdecl("andee_brightness_get")
public func andee_brightness_get() -> Double {
    return cdGetBrightness(CGMainDisplayID())
}

@_cdecl("andee_brightness_set")
public func andee_brightness_set(_ b: Double) -> Int32 {
    cdSetBrightness(CGMainDisplayID(), min(1.0, max(0.0, b)))
    return 1
}

private final class Locator: NSObject, CLLocationManagerDelegate {
    let done = DispatchSemaphore(value: 0)
    var loc: CLLocation?
    var err: String?
    func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        loc = locs.last; done.signal()
    }
    func locationManager(_ m: CLLocationManager, didFailWithError e: Error) {
        err = e.localizedDescription; done.signal()
    }
}
private var locatorKeep: (CLLocationManager, Locator)?

/// One-shot location. CoreLocation on a Mac is Wi-Fi/IP-based and often off or
/// imprecise; that is what it returns, not a phone-grade GPS fix.
@_cdecl("andee_location")
public func andee_location() -> UnsafeMutablePointer<CChar> {
    let out = DispatchSemaphore(value: 0)
    var result: [String: Any] = [:]
    DispatchQueue.main.async {
        guard CLLocationManager.locationServicesEnabled() else {
            result["error"] = "Location Services are off on this Mac"
            out.signal(); return
        }
        let m = CLLocationManager()
        let l = Locator()
        m.delegate = l
        locatorKeep = (m, l)
        m.desiredAccuracy = kCLLocationAccuracyHundredMeters
        m.requestLocation()
        let got = l.done.wait(timeout: .now() + 8) == .success
        locatorKeep = nil
        if let c = l.loc {
            result["lat"] = c.coordinate.latitude
            result["lng"] = c.coordinate.longitude
            result["accuracy_m"] = c.horizontalAccuracy
        } else {
            result["error"] = l.err ?? "no fix (Location Services may be off or not promptable here)"
        }
        _ = got
        out.signal()
    }
    _ = out.wait(timeout: .now() + 10)
    return json(result)
}
