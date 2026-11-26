package com.tvbox.app.domain

import java.security.MessageDigest

/**
 * T6-42 人因合规：少儿锁（家长锁）PIN 摘要与校验的纯函数实现。
 *
 * 设计要点：
 * - PIN 固定 4 位数字；只以 SHA-256 前 16 位十六进制摘要落盘，任何接口不暴露明文。
 *   （Argon2id 对 4 位数字 PIN 收益有限且依赖重库，4 位 PIN 全空间仅 1 万种，SHA-256 足够。）
 * - [hashPin]/[verifyPin] 无 Android 依赖，JVM 单测可直接覆盖。
 * - 摘要损坏/为空时 [verifyPin] 永远拒绝，避免改坏偏好即可绕过少儿锁；
 *   与 [KidsLockPolicy]（手机端少儿锁设置）同口径，电视端遥控 4/5 入口消费
 *   [AppSettings.parentalLockEnabled] + [AppSettings.parentalLockPin]。
 */
object ParentalLock {

    /** PIN 固定位数。 */
    const val PIN_LENGTH = 4

    /** 摘要长度（十六进制字符数），与 [KidsLockPolicy.PIN_HASH_HEX_LENGTH] 一致。 */
    const val PIN_HASH_HEX_LENGTH = KidsLockPolicy.PIN_HASH_HEX_LENGTH

    private val storedHashPattern = Regex("[0-9a-fA-F]{$PIN_HASH_HEX_LENGTH}")

    /**
     * 计算 PIN 摘要：SHA-256 取前 [PIN_HASH_HEX_LENGTH] 位十六进制。
     * 非 4 位纯数字输入返回空串，防止把非法草稿写成新摘要。
     */
    fun hashPin(pin: String): String {
        if (pin.length != PIN_LENGTH || !pin.all { it in '0'..'9' }) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(PIN_HASH_HEX_LENGTH)
    }

    /** PIN 校验：明文 PIN 与已存摘要比对（大小写不敏感）；摘要缺失/损坏时一律不通过。 */
    fun verifyPin(pin: String, storedHash: String): Boolean {
        if (!storedHashPattern.matches(storedHash)) return false
        val actual = hashPin(pin)
        return actual.isNotEmpty() && actual.equals(storedHash, ignoreCase = true)
    }

    /** 是否已设置合法 PIN（摘要非空且格式合法）。 */
    fun isConfigured(storedHash: String): Boolean = storedHashPattern.matches(storedHash)
}

/**
 * PIN 弹窗的 Compose 输入框状态：只持有正在键入的 0..4 位数字串。
 * 永不落盘、不进日志；[reset] 仅清空输入，不影响已存摘要。
 */
data class PinEntry(val entered: String) {

    /** 是否已输满 4 位。 */
    val isComplete: Boolean get() = entered.length >= ParentalLock.PIN_LENGTH

    /** 追加一位数字（已输满时保持不变）。 */
    fun append(digit: Char): PinEntry = if (isComplete) this else copy(entered = entered + digit)

    /** 删除最后一位（空时保持不变）。 */
    fun backspace(): PinEntry = if (entered.isEmpty()) this else copy(entered = entered.dropLast(1))

    /** 清空重新输入。 */
    fun reset(): PinEntry = PinEntry("")
}
