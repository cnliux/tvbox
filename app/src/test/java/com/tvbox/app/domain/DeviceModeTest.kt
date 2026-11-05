package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceModeTest {

    // ── 存储迁移（A2.6）：旧版本已保存的值视为手动值，未保存默认自动 ──────────────

    @Test
    fun `missing storage key defaults to Auto`() {
        assertEquals(DeviceMode.Auto, DeviceMode.fromStorageKey(null))
        assertEquals(DeviceMode.Auto, DeviceMode.fromStorageKey(""))
    }

    @Test
    fun `legacy keys map to manual overrides`() {
        assertEquals(DeviceMode.Television, DeviceMode.fromStorageKey("tv"))
        assertEquals(DeviceMode.Mobile, DeviceMode.fromStorageKey("mobile"))
    }

    @Test
    fun `auto key and unknown key fall back to Auto`() {
        assertEquals(DeviceMode.Auto, DeviceMode.fromStorageKey("auto"))
        assertEquals(DeviceMode.Auto, DeviceMode.fromStorageKey("bogus"))
    }

    // ── 自动识别判定矩阵（A2.2）─────────────────────────────────────────────────

    @Test
    fun `leanback system always detects television`() {
        assertEquals(DeviceMode.Television, DeviceMode.detect(isLeanback = true, screenWidthDp = 411, hasTouch = true))
        assertEquals(DeviceMode.Television, DeviceMode.detect(isLeanback = true, screenWidthDp = 960, hasTouch = false))
    }

    @Test
    fun `no touch or large screen detects television`() {
        assertEquals(DeviceMode.Television, DeviceMode.detect(isLeanback = false, screenWidthDp = 800, hasTouch = true))
        assertEquals(DeviceMode.Television, DeviceMode.detect(isLeanback = false, screenWidthDp = 411, hasTouch = false))
    }

    @Test
    fun `touch small screen detects mobile`() {
        assertEquals(DeviceMode.Mobile, DeviceMode.detect(isLeanback = false, screenWidthDp = 411, hasTouch = true))
        assertEquals(DeviceMode.Mobile, DeviceMode.detect(isLeanback = false, screenWidthDp = 360, hasTouch = true))
    }

    @Test
    fun `mid range touch screen stays television`() {
        // 600-719dp（平板/折叠屏）保守取电视布局。
        assertEquals(DeviceMode.Television, DeviceMode.detect(isLeanback = false, screenWidthDp = 650, hasTouch = true))
    }

    // ── 生效模式优先级（A2.3）：手动值最高，Auto 才参与识别 ──────────────────────

    @Test
    fun `manual overrides beat detection`() {
        assertEquals(DeviceMode.Television, DeviceMode.Television.resolveEffective(DeviceMode.Mobile))
        assertEquals(DeviceMode.Mobile, DeviceMode.Mobile.resolveEffective(DeviceMode.Television))
    }

    @Test
    fun `auto uses detection result`() {
        assertEquals(DeviceMode.Mobile, DeviceMode.Auto.resolveEffective(DeviceMode.Mobile))
        assertEquals(DeviceMode.Television, DeviceMode.Auto.resolveEffective(DeviceMode.Television))
    }

    @Test
    fun `auto with invalid detection falls back to television`() {
        assertEquals(DeviceMode.Television, DeviceMode.Auto.resolveEffective(DeviceMode.Auto))
    }

    @Test
    fun `app settings default preference is Auto`() {
        assertEquals(DeviceMode.Auto, AppSettings().deviceMode)
    }
}
