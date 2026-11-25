package com.tvbox.app.domain

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-09 遥控器设置三件套的领域回归：
 * - scanCode / deviceId+keyCode / keyCode 三级匹配优先级；
 * - 冲突发现与「保留哪个」裁决（动作级 tombstone）；
 * - 三套预设 + `remote_profile.json` 导出 / 导入 / 校验和防篡改。
 */
class RemoteProfileTest {

    @Test
    fun `learned scan code distinguishes keys reporting the same keyCode`() {
        // 魔盒把 OK 与 MEDIA_PLAY_PAUSE 都报成 85：只能靠 scanCode 区分。
        val bindings = RemoteKeyBindings.Default
            .withBinding(RemoteKeyAction.Confirm, 85)
            .learnKey(RemoteKeyAction.PlayPause, "box-1", 85, 200)
            .learnKey(RemoteKeyAction.Confirm, "box-1", 85, 201)

        assertEquals(RemoteKeyAction.PlayPause, bindings.resolveHardware("box-1", 85, 200))
        assertEquals(RemoteKeyAction.Confirm, bindings.resolveHardware("box-1", 85, 201))
        // 未学习的 scanCode 退回 deviceId+keyCode 层；再换设备才完全没有强信号。
        assertEquals(RemoteKeyAction.PlayPause, bindings.resolveHardware("box-1", 85, 999))
        assertNull(bindings.resolveHardware("unknown-box", 85, 999))
    }

    @Test
    fun `device key mapping outranks plain keyCode but yields to scanCode`() {
        val bindings = RemoteKeyBindings.Default
            .learnKey(RemoteKeyAction.Confirm, "box-1", 85, 0)
            .learnKey(RemoteKeyAction.PlayPause, "box-2", 85, 0)

        assertEquals(RemoteKeyAction.Confirm, bindings.resolveHardware("box-1", 85, 0))
        assertEquals(RemoteKeyAction.PlayPause, bindings.resolveHardware("box-2", 85, 0))
        assertNull(bindings.resolveHardware("unknown", 85, 0))
        // 无强信号时回退整表（枚举序首个命中 = PlayPause），与改动前一致。
        assertEquals(RemoteKeyAction.PlayPause, bindings.resolve("unknown", 85, 0))

        val withScan = bindings.learnKey(RemoteKeyAction.Confirm, "box-1", 85, 300)
        assertEquals(
            RemoteKeyAction.Confirm,
            withScan.resolveHardware("box-2", 85, 300),
        )
    }

    @Test
    fun `conflict is detected and keepOnly leaves a single owner for the key`() {
        val bindings = RemoteKeyBindings.Default
            .withBinding(RemoteKeyAction.Previous, KeyEvent.KEYCODE_MENU)
        val conflicts = remoteBindingConflicts(bindings)

        val menuConflict = conflicts.first { it.keyCode == KeyEvent.KEYCODE_MENU }
        assertTrue(menuConflict.actions.contains(RemoteKeyAction.Menu))
        assertTrue(menuConflict.actions.contains(RemoteKeyAction.Previous))
        assertTrue(menuConflict.summary.contains("菜单"))

        val keepMenu = bindings.keepOnly(RemoteKeyAction.Menu, KeyEvent.KEYCODE_MENU)
        assertEquals(listOf(RemoteKeyAction.Menu), keepMenu.actionsFor(KeyEvent.KEYCODE_MENU))
        // 上一集回到默认 MEDIA_PREVIOUS，而不是被彻底清空。
        assertTrue(keepMenu.keysFor(RemoteKeyAction.Previous).contains(KeyEvent.KEYCODE_MEDIA_PREVIOUS))

        val keepPrevious = bindings.keepOnly(RemoteKeyAction.Previous, KeyEvent.KEYCODE_MENU)
        assertEquals(listOf(RemoteKeyAction.Previous), keepPrevious.actionsFor(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun `encode decode round trip preserves learned data tombstones and preset`() {
        val bindings = RemoteKeyBindings.Default
            .withBinding(RemoteKeyAction.Previous, KeyEvent.KEYCODE_MENU)
            .learnKey(RemoteKeyAction.PlayPause, "box-9", 85, 200)
            .withPreset("a11y")
            .keepOnly(RemoteKeyAction.Menu, KeyEvent.KEYCODE_MENU)

        assertEquals(bindings, RemoteKeyBindings.decode(bindings.encode()))
        // 老格式（仅 `action:code`）继续可用。
        val legacy = RemoteKeyBindings.decode("previous:82,garbage,menu:not-a-number")
        assertEquals(setOf(KeyEvent.KEYCODE_MENU), legacy.keysFor(RemoteKeyAction.Previous))
        assertEquals("custom", legacy.preset)
    }

    @Test
    fun `three factory presets are exported with distinct feel settings`() {
        assertEquals(listOf("standard", "legacy6", "a11y"), RemotePreset.entries.map { it.id })
        assertEquals(
            setOf(KeyEvent.KEYCODE_DPAD_CENTER),
            RemotePreset.Legacy6.bindings.keysFor(RemoteKeyAction.PlayPause),
        )
        assertEquals(450, RemotePreset.Accessibility.feel.debounceCrossKeyMs)
        assertEquals(0, RemotePreset.Accessibility.feel.directTuneTimeoutMs)
        assertEquals(4, RemotePreset.Accessibility.feel.focusRingDp)
        assertEquals("full", RemotePreset.Accessibility.feel.announceLevel)
    }

    @Test
    fun `profile export import round trip preserves bindings feel and checksum`() {
        val bindings = RemoteKeyBindings.Default
            .withBinding(RemoteKeyAction.Previous, KeyEvent.KEYCODE_MENU)
            .learnKey(RemoteKeyAction.Confirm, "box-1", 23, 200)
            .withPreset("a11y")
        val json = RemoteProfile.fromBindings(bindings, "sha256:device", "2026-11-25T10:00:00+08:00").toJson()

        assertTrue(json.contains("\"checksum\":\"sha256:"))
        val parsed = RemoteProfile.parse(json)
        assertNull(parsed.error)
        val profile = parsed.profile!!
        assertEquals(2, profile.schemaVersion)
        assertEquals("a11y", profile.preset)
        assertEquals(bindings, profile.bindings)
        assertEquals(RemotePreset.Accessibility.feel, profile.feel)
        assertEquals("sha256:device", profile.deviceFingerprint)
    }

    @Test
    fun `tampered profile and newer schema version are rejected`() {
        val json = RemoteProfile.fromBindings(RemoteKeyBindings.Default.withPreset("standard")).toJson()
        val tampered = json.replace("\"preset\":\"standard\"", "\"preset\":\"a11y\"")
        val checksumError = RemoteProfile.parse(tampered)
        assertNotNull(checksumError.error)
        assertTrue(checksumError.error!!.contains("校验失败"))
        assertNull(checksumError.profile)

        val bodyOnly = json.replace(Regex(",\"checksum\":\"sha256:[0-9a-f]+\""), "")
        val tooNew = bodyOnly.replace("\"schemaVersion\":2", "\"schemaVersion\":99")
        val versionError = RemoteProfile.parse(tooNew)
        assertNotNull(versionError.error)
        assertTrue(versionError.error!!.contains("版本"))

        // 坏 JSON / 非对象根节点只返回错误文案，不抛异常。
        assertNotNull(RemoteProfile.parse("{").error)
        assertNotNull(RemoteProfile.parse("[]").error)
    }

    @Test
    fun `legacy v1 keys are imported as custom bindings`() {
        val legacyJson = """
            {"schemaVersion":1,"preset":"custom",
             "bindings":{"keys":{"previous":[82]}}}
        """.trimIndent()
        val result = RemoteProfile.parse(legacyJson)
        assertNull(result.error)
        val profile = checkNotNull(result.profile)
        assertEquals(1, profile.schemaVersion)
        assertEquals(setOf(KeyEvent.KEYCODE_MENU), profile.bindings.keysFor(RemoteKeyAction.Previous))
        assertTrue(profile.bindings.custom[RemoteKeyAction.Menu.id].isNullOrEmpty())
    }
}
