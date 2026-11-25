package com.tvbox.app.domain

import java.security.MessageDigest

/**
 * T6-42 人因合规策略：少儿锁 PIN 摘要与三项开关的纯函数判定。
 *
 * 设计要点：
 * - PIN 只以 SHA-256 前 16 位十六进制摘要落盘，任何接口都不暴露明文；
 * - 不在这里做 UI 拦截，运行时拦截（播放/导航）由消费侧接入本策略；
 * - 摘要损坏时按「锁仍开启、但任何 PIN 都不通过」处理，避免改坏偏好即可绕过少儿锁。
 */
object KidsLockPolicy {

    /** 与 LegalTerms.hash 同口径：摘要长度取 16 个十六进制字符，避免整段摘要进日志。 */
    const val PIN_HASH_HEX_LENGTH = 16

    private val storedHashPattern = Regex("[0-9a-fA-F]{$PIN_HASH_HEX_LENGTH}")

    /** PIN 规则：4~6 位纯数字。 */
    fun isValidPin(pin: String): Boolean =
        pin.length in 4..6 && pin.all { it in '0'..'9' }

    /** 计算 PIN 摘要；调用方必须先行校验 [isValidPin]，空串/非法 PIN 不用于设置锁。 */
    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
            .take(PIN_HASH_HEX_LENGTH)
    }

    /** 摘要格式是否合法（十六进制、长度一致）。 */
    fun isValidStoredHash(hash: String): Boolean = storedHashPattern.matches(hash)

    /** 是否已设置少儿锁：只要存了非空摘要就视为开启，损坏摘要也不会静默解锁。 */
    fun isEnabled(storedHash: String): Boolean = storedHash.isNotBlank()

    /** PIN 校验：明文 PIN 与已存摘要比对，大小写不敏感。 */
    fun matches(pin: String, storedHash: String): Boolean {
        if (!isValidPin(pin) || !isValidStoredHash(storedHash)) return false
        return hashPin(pin).equals(storedHash, ignoreCase = true)
    }

    /** 使用时长就寝锁是否生效；0 表示无限制。 */
    fun isScreenTimeLimitActive(limitMinutes: Int): Boolean = limitMinutes > 0

    /** 已用时长达到/超过上限时是否锁定。 */
    fun shouldLockForScreenTime(usedMinutes: Long, limitMinutes: Int): Boolean =
        isScreenTimeLimitActive(limitMinutes) && usedMinutes >= limitMinutes

    /** 车载模式下是否拦截需要复杂交互的动作（深层菜单、长表单等）。 */
    fun shouldRestrictInteraction(drivingMode: Boolean, needsComplexInteraction: Boolean): Boolean =
        drivingMode && needsComplexInteraction
}
