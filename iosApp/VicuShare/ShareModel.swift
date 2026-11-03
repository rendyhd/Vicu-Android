import Foundation
import UniformTypeIdentifiers

/// What was shared, read from the item providers, and the folder it is saved in for the app:
/// `share-inbox/<id>.part/` while the sheet is open, renamed to `share-inbox/<id>/` on Add
/// (the app imports only finished folders). Files are copied in as they load.
@MainActor
final class ShareModel: ObservableObject {
    @Published var title = ""
    @Published var notes = ""
    @Published private(set) var files: [String] = []
    @Published private(set) var loading = true
    @Published private(set) var signedIn = true
    @Published var error: String?

    var canAdd: Bool {
        signedIn && !loading && (!title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !files.isEmpty)
    }

    private let id: String
    private let inbox: URL?
    /// Where the share is put together until Add; read from the item providers' queues too.
    nonisolated private let staging: URL?

    init() {
        let group = Bundle.main.object(forInfoDictionaryKey: "VicuAppGroup") as? String ?? ""
        let id = UUID().uuidString.lowercased()
        let inbox = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("share-inbox", isDirectory: true)
        self.id = id
        self.inbox = inbox
        staging = inbox?.appendingPathComponent("\(id).part", isDirectory: true)
    }

    func load(_ items: [NSExtensionItem]) async {
        signedIn = Self.appIsSignedIn(inbox)
        var subject: String?
        var texts: [String] = []
        var urls: [String] = []

        for item in items {
            if subject == nil, let title = item.attributedTitle?.string.trimmed, !title.isEmpty {
                subject = title
            }
            if let content = item.attributedContentText?.string.trimmed, !content.isEmpty {
                texts.append(content)
            }
            for provider in item.attachments ?? [] {
                if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                    if let url = await provider.loadURL(UTType.fileURL), let name = copyFile(url) { files.append(name) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                    if let url = await provider.loadURL(UTType.url) { urls.append(url.absoluteString) }
                } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                    if let text = await provider.loadText(), !text.trimmed.isEmpty { texts.append(text.trimmed) }
                } else if let type = Self.fileType(provider), let name = await copyRepresentation(provider, type) {
                    files.append(name)
                }
            }
        }

        // Safari and others send a page as its title (plain text) and its URL: the title is the
        // subject then, as the share subject is on Android.
        if subject == nil, !urls.isEmpty, let first = texts.first, !first.contains(where: \.isNewline), first.count <= 200 {
            subject = first
            texts.removeFirst()
        }
        // As Android's share (SharedContent): the subject is the title, else the first line of
        // the text; the text goes into the notes unless it is all the title already says. Files
        // start without a title: the subject that comes with them is the sender's name for them.
        var seen = Set<String>()
        let text = (texts + urls).filter { seen.insert($0).inserted }.joined(separator: "\n")
        if files.isEmpty {
            let firstLine = text.split(whereSeparator: \.isNewline).first.map { String($0.prefix(100)) }
            title = subject ?? firstLine ?? ""
            notes = text == title ? "" : text
        } else {
            title = ""
            notes = text == subject ? "" : text
        }
        loading = false
    }

    func save() throws {
        guard let inbox, let staging else { throw CocoaError(.fileNoSuchFile) }
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        let item: [String: Any] = [
            "title": title.trimmed,
            "notes": notes.trimmed,
            "files": files,
        ]
        let data = try JSONSerialization.data(withJSONObject: item, options: [.sortedKeys])
        try data.write(to: staging.appendingPathComponent("item.json"))
        try FileManager.default.moveItem(at: staging, to: inbox.appendingPathComponent(id, isDirectory: true))
    }

    func discard() {
        if let staging { try? FileManager.default.removeItem(at: staging) }
    }

    // MARK: - Files

    /// The type to copy a provider's content as: a JPEG or PNG when offered (a HEIC photo is not
    /// shown by every browser), else the first data type it has.
    private static func fileType(_ provider: NSItemProvider) -> UTType? {
        let types = provider.registeredTypeIdentifiers.compactMap(UTType.init)
        return types.first { $0 == .jpeg } ?? types.first { $0 == .png } ?? types.first { $0.conforms(to: .data) }
    }

    private func copyRepresentation(_ provider: NSItemProvider, _ type: UTType) async -> String? {
        await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                // The file is only there until this returns: copy it now.
                let name = url.flatMap { url -> String? in
                    var base = provider.suggestedName ?? url.deletingPathExtension().lastPathComponent
                    if base.isEmpty { base = "file" }
                    let ext = type.preferredFilenameExtension ?? url.pathExtension
                    let fileName = (base as NSString).pathExtension.isEmpty && !ext.isEmpty ? "\(base).\(ext)" : base
                    return self.copy(url, as: fileName)
                }
                continuation.resume(returning: name)
            }
        }
    }

    private func copyFile(_ url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return copy(url, as: url.lastPathComponent)
    }

    /// Copies [source] into the staging folder under a free name; nonisolated, since the item
    /// provider calls back on its own queue and the file is gone once that call returns.
    nonisolated private func copy(_ source: URL, as fileName: String) -> String? {
        guard let dir = staging?.appendingPathComponent("files", isDirectory: true) else { return nil }
        let manager = FileManager.default
        try? manager.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = fileName.replacingOccurrences(of: "/", with: "_")
        let stem = (safe as NSString).deletingPathExtension
        let ext = (safe as NSString).pathExtension
        var name = safe
        var counter = 2
        while manager.fileExists(atPath: dir.appendingPathComponent(name).path) {
            name = ext.isEmpty ? "\(stem) \(counter)" : "\(stem) \(counter).\(ext)"
            counter += 1
        }
        do {
            try manager.copyItem(at: source, to: dir.appendingPathComponent(name))
            return name
        } catch {
            return nil
        }
    }

    /// The app's own word on whether anyone is signed in (the widgets' catalog it writes).
    private static func appIsSignedIn(_ inbox: URL?) -> Bool {
        guard let catalog = inbox?.deletingLastPathComponent().appendingPathComponent("widgets/catalog.json"),
              let data = try? Data(contentsOf: catalog),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        return json["signedIn"] as? Bool ?? false
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

private extension NSItemProvider {
    func loadURL(_ type: UTType) async -> URL? {
        await withCheckedContinuation { continuation in
            loadItem(forTypeIdentifier: type.identifier) { item, _ in
                switch item {
                case let url as URL: continuation.resume(returning: url)
                case let data as Data: continuation.resume(returning: URL(dataRepresentation: data, relativeTo: nil))
                case let string as String: continuation.resume(returning: URL(string: string))
                default: continuation.resume(returning: nil)
                }
            }
        }
    }

    func loadText() async -> String? {
        await withCheckedContinuation { continuation in
            loadItem(forTypeIdentifier: UTType.plainText.identifier) { item, _ in
                switch item {
                case let text as String: continuation.resume(returning: text)
                case let data as Data: continuation.resume(returning: String(data: data, encoding: .utf8))
                case let text as NSAttributedString: continuation.resume(returning: text.string)
                default: continuation.resume(returning: nil)
                }
            }
        }
    }
}
