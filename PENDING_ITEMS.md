# TVBox 改进清单 — 待定项（Pending Items）

> 生成日期：2026-11-26 ｜ 最后核对基线：`v1.0.47`（`versionCode=20047`） ｜ 用途：供人工检查与后续排期
> 说明：本文件列出本轮 400 条改进清单中**未实现或仅部分实现**的条目。已实现条目移入「已闭环」存档区（见 §七），不删除（保留决策痕迹）。

## 一、播放器进阶（域L，需深度开发）

| 编号 | 条目 | 现状 | 原因 / 备注 |
|---|---|---|---|
| #242 | HDR 渲染 | 未实现 | ExoPlayer 未配置 HDR/色调映射 |
| #243 | 帧率设置 | 未实现 | 未提供帧率选择 |
| #247 | 音画同步精细调节 | 未实现 | 未提供音画偏移调节 |
| #251 | 后台音频/播放 | 未实现 | 退后台策略为暂停，无后台播放 |
| #256 | AB 循环 | 未实现 | 无 A/B 区间循环 |
| #257 | 播放器截图 | 未实现 | 无帧截图导出 |

## 二、直播进阶（域L）

| 编号 | 条目 | 现状 | 原因 / 备注 |
|---|---|---|---|
| #264 | 时移 | 未实现 | 直播源无时移支持 |
| #265 | 回看 | 未实现 | 无回看接口/UI |
| #266 | 录制 | 未实现 | 无录制管线 |
| #267 | 多画面 | 未实现 | 无多画面合成 |
| #268 | 自动测速 | 部分 | 有线路延迟记录，无主动自动测速 |

## 三、源 / 搜索 / 历史 / OTA（域L）

| 编号 | 条目 | 现状 | 原因 / 备注 |
|---|---|---|---|
| #296 | 失效源清理 | 部分 | v1.0.36 源健康管理 + v1.0.39 播放管家线路质量统计已做；设置页可清空统计 |
| #297 | 跨设备同步 | 未实现 | 收藏/历史仅本地 |
| #298 | 增量更新 | 未实现 | OTA 仅全量 APK，无增量补丁 |
| #302 | 通道切换 | 未实现 | 无更新通道切换 UI |

## 四、稳定性与监控（域L）

| 编号 | 条目 | 现状 | 原因 / 备注 |
|---|---|---|---|
| #306 | ANR 堆栈监控 | 部分 | 有主线程耗时记录，无独立 ANR 采集 |
| #310 | 主线程监控 | 部分 | 部分路径有耗时记录，未全覆盖 |
| #311 | 泄漏检测 | 未实现 | 未接入 LeakCanary（仅 Debug 配置） |
| #312 | 存储检查 | 部分 | 有下载目录处理，无统一空间检查 |
| #313 | 电池优化 | 未实现 | 无电池优化引导 |

## 五、安全 / 配置遗留（域J-K）

| 编号 | 条目 | 现状 | 原因 / 备注 |
|---|---|---|---|
| #185 | 明文 HTTP 限制 | 部分 | `usesCleartextTraffic=true`（直播 http 源需要），无域白名单 |
| #196 | 网络安全配置 | 部分 | 无 network_security_config.xml 文件 |
| #226 | 反馈日志（TV 端） | 部分 | 手机端已落地导出入口；TV 端无系统分享 UI 故简化 |

## 六、不适用（明确排除，非待定）

| 编号 | 条目 | 说明 |
|---|---|---|
| #194 | 截图保护 | 应用无账号/支付/敏感页面 |
| #199 | 上报开关 | 无远程崩溃上报服务，崩溃日志本地留存 |

## 七、已闭环（v1.0.36–v1.0.43）

> 以下条目经代码核对确认已实现，从待定区移入存档（不删除，保留决策痕迹）。

| 编号 | 条目 | 实现版本 / 落点 |
|---|---|---|
| #244 | 音频直通 | v1.0.41：`auto / stereo / passthrough` 三档 + 不支持自动回落立体声 |
| #250 | PiP 画中画 | v1.0.42：`PlayerScreen.enterPictureInPictureSafely` + `AndroidManifest supportsPictureInPicture=true`，失败不抛异常退后台续音频 |
| #254 | 媒体键控制 | v1.0.42：`rememberPlayerMediaSession` 接入 LiveScreen / MobileLiveScreen / PlatformLiveScreen |
| #291 | 历史批量删除 | v1.0.38：`ui/history/` 长按多选批量删除 |
| #307 | 崩溃聚类 | v1.0.42：`CrashHandler` 用 SHA-256 对脱敏堆栈首行做同类聚类 |

## 八、机器可读索引（S8-44 `pending-lint` 消费）

> 每条一行，格式固定：`<!-- id:#N state:open|done|wontfix owner:@x since:x.y.z verify:grep:<token> -->`。
> `open` + `verify` 已在源码命中 → CI 红（提示移到已闭环）；`open` 超过 2 个 minor → 周报 @owner；
> `wontfix` 必须带 `reason:`。校验脚本：`python3 tools/pending_todo_lint.py --root .`

<!-- 补充清单已闭环项：代码核对确认，state=done -->
<!-- id:S3-14 state:done verify:grep:ResetPathPolicy.kt -->
<!-- id:S3-15 state:done verify:grep:trimByVolume -->
<!-- id:S5-27 state:done verify:grep:SearchFlowBuilder.kt -->
<!-- id:S5-28 state:done verify:grep:PosterImage.kt -->
<!-- id:S8-42 state:done verify:grep:bug_report.yml -->
<!-- id:S8-45 state:done verify:grep:SECURITY.md -->
<!-- id:#244 state:done -->
<!-- id:#250 state:done -->
<!-- id:#254 state:done -->
<!-- id:#291 state:done -->
<!-- id:#307 state:done -->
<!-- id:#302 state:done verify:grep:UpdateChannelPolicy.kt -->

<!-- 仍待办项：owner 与 since 必填，超过 2 个 minor 会自动进周报 issue -->
<!-- id:#242 state:open owner:@cnliux since:1.0.31 verify:grep:ToneMapping -->
<!-- id:#243 state:open owner:@cnliux since:1.0.31 -->
<!-- id:#247 state:open owner:@cnliux since:1.0.31 -->
<!-- id:#256 state:open owner:@cnliux since:1.0.31 -->
<!-- id:#297 state:open owner:@cnliux since:1.0.31 -->
<!-- id:#311 state:wontfix reason:仅 Debug 构建接入内存检测工具，Release 侧载包不引入检测开销 -->

<!-- 补充清单安全/工程项（详见 待执行/TVBox_v1.0.42_补充问题清单_安全合规与工程化盲区.md） -->
<!-- id:S1-01 state:open owner:@cnliux since:1.0.43 -->
<!-- id:S1-02 state:open owner:@cnliux since:1.0.43 -->
<!-- id:S1-03 state:open owner:@cnliux since:1.0.43 -->
<!-- id:S1-04 state:open owner:@cnliux since:1.0.43 -->
<!-- id:S2-08 state:open owner:@cnliux since:1.0.43 -->
<!-- id:S4-20 state:open owner:@cnliux since:1.0.43 -->
<!-- id:S7-36 state:open owner:@cnliux since:1.0.43 -->
