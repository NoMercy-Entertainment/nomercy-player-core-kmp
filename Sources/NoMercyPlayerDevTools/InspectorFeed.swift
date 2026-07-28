// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

import Foundation
import NoMercyPlayerCore

/// One line of the stream, as SwiftUI sees it.
///
/// The library's own value rather than the engine's `InspectorEvent`, for the
/// same reason the subtitle renderer keeps its own cue type: the view is worth
/// asserting on, and asserting on it should not need a running player. The
/// engine's event converts into this on the way through.
public struct InspectorRow: Identifiable, Equatable, Sendable {
    public let id: Int64
    public let atMs: Int64
    public let name: String
    public let summary: String

    public init(id: Int64, atMs: Int64, name: String, summary: String) {
        self.id = id
        self.atMs = atMs
        self.name = name
        self.summary = summary
    }
}

/// What the inspector is holding, observable.
///
/// SwiftUI cannot watch a Kotlin `StateFlow` directly, so this reads it and
/// republishes. The read is a task tied to `attach`, and `detach` ends it —
/// a debug view left mounted after the player goes away would otherwise keep a
/// subscription alive against a player nobody is using.
@MainActor
public final class InspectorFeed: ObservableObject {

    @Published public private(set) var rows: [InspectorRow] = []

    /// The three loudest event names and their tallies, as one line.
    ///
    /// Reading a stream one line at a time is how you miss that the same event
    /// fired four hundred times.
    @Published public private(set) var loudest: String = ""

    private var reader: Task<Void, Never>?

    public init() {}

    deinit {
        reader?.cancel()
    }

    public func attach(to inspector: PlayerInspector) {
        detach()
        reader = Task { [weak self] in
            for await events in inspector.events {
                guard !Task.isCancelled else { return }
                self?.show(events.map(InspectorRow.init(event:)))
            }
        }
    }

    public func detach() {
        reader?.cancel()
        reader = nil
    }

    /// The seam a fixture drives. The view renders whatever is here and does not
    /// know whether a player put it there.
    public func show(_ rows: [InspectorRow]) {
        self.rows = rows
        loudest = InspectorFeed.tally(rows)
    }

    static func tally(_ rows: [InspectorRow]) -> String {
        var counts: [String: Int] = [:]
        for row in rows {
            counts[row.name, default: 0] += 1
        }
        return counts
            .sorted { left, right in
                // Count first, then name. Two events with the same tally would
                // otherwise swap places between renders, which reads as the
                // stream changing when nothing did.
                left.value == right.value ? left.key < right.key : left.value > right.value
            }
            .prefix(3)
            .map { "\($0.key)×\($0.value)" }
            .joined(separator: "  ")
    }
}

extension InspectorRow {
    init(event: InspectorEvent) {
        self.init(
            id: event.sequence,
            atMs: event.atMs,
            name: event.name,
            summary: event.summary
        )
    }
}
