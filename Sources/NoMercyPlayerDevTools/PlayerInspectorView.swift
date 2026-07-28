// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

import SwiftUI

/// The event stream, on screen, over whatever is playing.
///
/// The SwiftUI twin of `PlayerInspectorOverlay`: same panel, same columns, same
/// colours. Two toolkits drawing the same tool differently is how a bug report
/// from an iPhone stops matching one from an Android box.
///
/// It follows the tail. A debug stream that stops scrolling the moment something
/// interesting happens is one you have to fight while reading it.
public struct PlayerInspectorView: View {

    @ObservedObject private var feed: InspectorFeed

    public init(feed: InspectorFeed) {
        self.feed = feed
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            header

            ScrollViewReader { scroller in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        ForEach(feed.rows) { row in
                            line(row).id(row.id)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onChange(of: feed.rows.count) { _ in
                    guard let last = feed.rows.last else { return }
                    scroller.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Self.panel)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Player event inspector")
    }

    private var header: some View {
        HStack {
            Text("\(feed.rows.count) event(s)")
                .font(Self.mono)
                .foregroundColor(Self.label)
            Spacer()
            Text(feed.loudest)
                .font(Self.mono)
                .foregroundColor(Self.label)
                .lineLimit(1)
                .truncationMode(.tail)
        }
    }

    private func line(_ row: InspectorRow) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(String(row.atMs))
                .font(Self.mono)
                .foregroundColor(Self.time)
            Text(row.name)
                .font(Self.mono)
                .foregroundColor(Self.name)
            Text(row.summary)
                .font(Self.mono)
                .foregroundColor(Self.summary)
                .lineLimit(1)
                .truncationMode(.tail)
        }
    }

    // Monospace and small, so the columns line up, and named colours rather than
    // the environment's. This panel is read on top of video, which is every
    // colour at once, and a debug tool that inherits a theme becomes unreadable
    // the first time somebody ships a light one.
    private static let mono: Font = .system(size: 11, design: .monospaced)
    private static let panel: Color = Color(white: 0.06).opacity(0.9)
    private static let label: Color = Color(white: 0.54)
    private static let time: Color = Color(white: 0.43)
    private static let name: Color = Color(red: 0.50, green: 0.82, blue: 1.0)
    private static let summary: Color = Color(white: 0.85)
}
