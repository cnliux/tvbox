package com.tvbox.app

import java.io.File

/**
 * 测试期定位仓库根文件的统一入口：Gradle 在不同 IDE / CI 下给 unit test 的
 * user.dir 可能是仓库根、也可能是 app 模块目录，这里向上找最多 6 层兼容两者。
 * 仅供构建期门禁的 JVM 单测读取源文件，不参与 App 运行时。
 */
internal object TestProjectFiles {
    fun find(relativePath: String): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            listOf(File(dir, relativePath), File(dir, "app/$relativePath")).forEach { candidate ->
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile ?: return error("找不到仓库文件：$relativePath")
        }
        error("找不到仓库文件：$relativePath（user.dir=${System.getProperty("user.dir")}）")
    }

    fun read(relativePath: String): String = find(relativePath).readText()
}
