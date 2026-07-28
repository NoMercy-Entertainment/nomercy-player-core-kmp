// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

import XCTest
@testable import NoMercyPlayerDevTools

@MainActor
final class InspectorFeedTests: XCTestCase {

    func testTheHeaderNamesTheThreeLoudestEvents() {
        let feed = InspectorFeed()

        feed.show([
            row(0, "time"), row(1, "time"), row(2, "time"),
            row(3, "play"), row(4, "play"),
            row(5, "pause"),
            row(6, "seeking"),
        ])

        XCTAssertEqual("time×3  play×2  pause×1", feed.loudest)
    }

    // Two names with the same tally have to land in the same order every time.
    // Left to a dictionary they swap between renders, which reads as the stream
    // changing when nothing did.
    func testATieIsBrokenByNameRatherThanByLuck() {
        let feed = InspectorFeed()

        feed.show([row(0, "zulu"), row(1, "alpha")])

        XCTAssertEqual("alpha×1  zulu×1", feed.loudest)
    }

    func testTheHeaderIsEmptyBeforeAnythingHasHappened() {
        XCTAssertEqual("", InspectorFeed().loudest)
        XCTAssertTrue(InspectorFeed().rows.isEmpty)
    }

    // The rows carry the sequence number as their identity, not their position.
    // Once the buffer has dropped its oldest entries the position stops meaning
    // anything, and a list keyed on it re-animates every row on every append.
    func testRowsAreIdentifiedByTheirSequenceNumber() {
        let feed = InspectorFeed()

        feed.show([row(97, "play"), row(98, "playing")])

        XCTAssertEqual([97, 98], feed.rows.map(\.id))
    }

    private func row(_ sequence: Int64, _ name: String) -> InspectorRow {
        InspectorRow(id: sequence, atMs: sequence * 10, name: name, summary: "")
    }
}
