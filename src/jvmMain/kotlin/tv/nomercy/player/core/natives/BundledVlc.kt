// -----------------------------------------------------------------------------
//  Copyright (c) NoMercy Entertainment
//
//  Licensed under the Apache License, Version 2.0. See LICENSE for details.
//
//  SPDX-License-Identifier: Apache-2.0
// -----------------------------------------------------------------------------

package tv.nomercy.player.core.natives

import java.io.File
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryProviderPriority

// The bundled libVLC, offered to vlcj ahead of everything else it would look at.
//
// vlcj finds libVLC by asking every registered provider for directories and
// searching them in priority order, and it discovers the providers through the
// JVM's own service loader. So this class does not have to be called from
// anywhere: putting it on the classpath is what installs it, and every consumer
// of this library gets the bundled runtime without writing a line — which is the
// entire point, because a consumer who has to write a line has not been given a
// library that works out of the box.
//
// Priority is one above the highest vlcj defines. That ordering is the
// behavioural contract: the payload this library pinned and verified wins, and a
// VLC the machine happens to have installed is the fallback underneath it. The
// other way round — which is what the default gives you — means the version and
// the plugin set differ per user, and a bug reproduces on one machine out of
// five.
//
// Returning nothing is a normal answer. No payload for this platform yet, an
// offline machine with nothing staged: vlcj moves down the list and finds the
// system install, exactly as it did before any of this existed.
public class BundledVlcDirectoryProvider : DiscoveryDirectoryProvider {

    override fun priority(): Int = DiscoveryProviderPriority.CONFIG_FILE + 1

    override fun supported(): Boolean = true

    override fun directories(): Array<String> =
        BundledVlc.directory()?.let { directory -> arrayOf(directory.absolutePath) } ?: emptyArray()
}

internal object BundledVlc {

    // Where libvlc itself sits inside the payload, which is not the payload root
    // on every platform.
    //
    // vlcj derives the plugin directory from wherever it found the library, and
    // its macOS rule is `<found>/../plugins` — that is the shape of a VLC.app
    // bundle, where the dylibs live in lib/ and the plugins are their sibling.
    // Windows and Linux use `<found>/plugins`. So the payload is laid out to
    // match each platform's own convention and this points one directory deeper
    // on macOS.
    fun directory(): File? {
        val root: File = NativeRuntimes.directory(NativeRuntimeKind.LIB_VLC) ?: return null
        val nested = File(root, "lib")
        return if (nested.isDirectory) nested else root
    }
}
