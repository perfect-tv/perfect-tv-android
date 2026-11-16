package top.rootu.perfecttv.core

import org.junit.Assert.*
import org.junit.Test

class UpdaterTest {
    @Test fun newerByNumbers() {
        assertTrue(Updater.isNewer("v1.2.0", "1.1.9"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.0"))
        assertTrue(Updater.isNewer("2.0", "1.9.9"))
        assertFalse(Updater.isNewer("v1.2.3", "1.2.3"))
        assertFalse(Updater.isNewer("1.2.0", "1.2.1"))
    }

    @Test fun buildAfterTagIsNotOlderThanTag() {
        assertFalse(Updater.isNewer("v1.2.3", "1.2.3-5-gabcdef"))
        assertFalse(Updater.isNewer("v1.2.3", "1.2.3-dirty"))
        assertTrue(Updater.isNewer("v1.2.4", "1.2.3-5-gabcdef"))
    }

    @Test fun devBuildWithoutTagGetsAnyRelease() {
        assertTrue(Updater.isNewer("v0.0.2", "0.0.1-dev-abc123"))
        assertTrue(Updater.isNewer("v1.0.0", "dev"))
        assertFalse(Updater.isNewer("garbage", "1.0.0"))
    }

    @Test fun parsesLatestRelease() {
        val json = """{"tag_name":"v1.4.0","draft":false,"prerelease":false,"body":"## What's Changed\n* fix by @x in https://github.com/a/b/pull/1\n\n**Full Changelog**: https://github.com/a/b/compare/v1.3.0...v1.4.0",
            "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":5},{"name":"app-release.apk","browser_download_url":"https://x/app-release.apk","size":1234}]}"""
        val i = Updater.parse(json)!!
        assertEquals("1.4.0", i.version)
        assertEquals("https://x/app-release.apk", i.url)
        assertEquals(1234L, i.size)
        assertEquals("What's Changed\n* fix by @x in", Updater.cleanNotes(i.notes))
    }

    @Test fun skipsDraftPrereleaseAndMissingApk() {
        assertNull(Updater.parse("""{"tag_name":"v2","draft":true,"assets":[{"name":"a.apk","browser_download_url":"u","size":1}]}"""))
        assertNull(Updater.parse("""{"tag_name":"v2","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u","size":1}]}"""))
        assertNull(Updater.parse("""{"tag_name":"v2","assets":[{"name":"a.zip","browser_download_url":"u","size":1}]}"""))
    }

    @Test fun jsonRoundTrip() {
        val i = Updater.Info("1.0.1", "заметки", "https://x/a.apk", 99)
        val back = Updater.fromJson(Updater.toJson(i))!!
        assertEquals("1.0.1", back.version); assertEquals("заметки", back.notes); assertEquals(99L, back.size)
        assertNull(Updater.fromJson("")); assertNull(Updater.fromJson("{}"))
    }
}
