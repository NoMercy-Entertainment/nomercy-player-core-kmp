// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.ui.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tv.nomercy.player.core.devtools.InspectorEvent
import tv.nomercy.player.core.devtools.PlayerInspector

// The event stream, on screen, over whatever is playing.
//
// A translucent panel rather than a screen of its own: the question it answers
// is "what did the player just do", and the answer is only useful next to what
// the player is showing. It takes a Modifier and draws nothing else, so where it
// sits is the caller's decision — over the chrome on a phone, in a side panel on
// a desktop, off entirely in a release build.
//
// It follows the tail. A debug stream that stops scrolling the moment something
// interesting happens is one you have to fight while reading it.
@Composable
public fun PlayerInspectorOverlay(
    inspector: PlayerInspector,
    modifier: Modifier = Modifier,
) {
    val events: List<InspectorEvent> by inspector.events.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.scrollToItem(events.lastIndex)
    }

    Column(
        modifier = modifier
            .background(PANEL)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = "Player event inspector" },
    ) {
        InspectorHeader(events.size, inspector.counts())

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(events, key = { it.sequence }) { event -> InspectorRow(event) }
        }
    }
}

// The counts before the lines.
//
// Reading a stream one line at a time is how you miss that the same event fired
// four hundred times. The three loudest are named because a full tally is a
// second stream to read.
@Composable
private fun InspectorHeader(total: Int, counts: Map<String, Int>) {
    val loudest: String = counts.entries
        .sortedByDescending { it.value }
        .take(HEADER_NAMES)
        .joinToString("  ") { "${it.key}×${it.value}" }

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = "$total event(s)", style = MONO.copy(color = LABEL))
        BasicText(
            text = loudest,
            style = MONO.copy(color = LABEL),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InspectorRow(event: InspectorEvent) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = event.atMs.toString().padStart(TIME_COLUMN),
            style = MONO.copy(color = TIME),
        )
        BasicText(text = event.name, style = MONO.copy(color = NAME))
        // Wrapped rather than cut at one line. A payload is the whole reason
        // to read a log line, and the widest ones are the interesting ones —
        // PlaybackMetrics carries eight fields and every screenshot of this
        // pane ended "bufferingEvents=0.0, bufferin…", which hides the numbers
        // the row exists to show.
        BasicText(
            text = event.summary,
            style = MONO.copy(color = SUMMARY),
            maxLines = SUMMARY_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Monospace and small. Every line is a name and a number in a column, and a
// proportional face makes columns that do not line up.
private val MONO: TextStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)

// Named colours rather than a theme. This panel is read on top of video, which
// is every colour at once, so it needs its own contrast rather than the host
// application's — and a debug tool that inherits a theme is a debug tool that
// becomes unreadable the first time somebody ships a light one.
private val PANEL: Color = Color(0xE6101014)
private val LABEL: Color = Color(0xFF8A8A94)
private val TIME: Color = Color(0xFF6E6E78)
private val NAME: Color = Color(0xFF7FD1FF)
private val SUMMARY: Color = Color(0xFFD8D8DE)

private const val HEADER_NAMES: Int = 3
private const val TIME_COLUMN: Int = 6

// Enough for a metrics snapshot, short of a payload taking the pane.
private const val SUMMARY_MAX_LINES = 3
