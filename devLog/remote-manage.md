# 局域网远程管理与视频分类屏蔽

## 2026-11-01 - 无 token 远程管理 + 视频源管理内“获取分类→点击屏蔽”

### 用户需求

- 删除旧的带 token 远程配置服务，改为同一局域网内直接访问、无需 token 的远程管理页。
- 远程页要能管理视频源与直播源、导入订阅、删除/恢复平台直播站点，并支持屏蔽某个分类。
- “屏蔽分类”指**视频源（MacCms）分类**，不是电视直播分组。
- 视频接口要支持通过 URL 导入 JSON / TVBox 格式，也支持直接粘贴内容。
- 将视频源管理功能设计进远程管理。
- 屏蔽方式改进：先“获取视频源分类”，再把分类渲染成可点击开关；屏蔽结果常驻保存为 JSON 文件。

### 文件与目的

- `app/src/main/java/com/tvbox/app/data/RemoteManageServer.kt`（新增）
  - 内置 `ServerSocket` 手写 HTTP 服务，优先端口 `9978`，被占用时随机端口；无 token，GET 渲染网页、POST 表单提交后 303 重定向回页面并带 `?message=` 提示。
  - 页面分区：视频源管理（列表、设为首页、分类、屏蔽/恢复、删除/恢复、全部测速排序、手动添加接口、URL/内容导入、分类开关区）、直播源、平台直播站点、AI 配置。
  - 视频分类区：已获取分类的 chips（点击即屏蔽/恢复）、已屏蔽列表（可单独恢复或全部恢复）、手动添加分类、`/blocked-categories.json` 链接。
- `app/src/main/java/com/tvbox/app/data/BlockedCategoriesStore.kt`（新增）
  - 屏蔽分类常驻保存在 `files/blocked_categories.json`；`Json(prettyPrint, encodeDefaults)` 保证空列表也写出 `"categories": []`，写入使用临时文件 + 覆盖，失败不影响启动。
- `app/src/main/java/com/tvbox/app/data/AppSettingsRepository.kt`
  - 读取设置时优先使用 JSON 文件里的屏蔽分类，文件不存在则回落到旧偏好并在首次升级时迁移写入 JSON；保存设置时同步写 JSON 与偏好。
- `app/src/main/java/com/tvbox/app/domain/AppSettings.kt`
  - 新增 `blockedVideoCategories`、`introSkipSeconds`、`outroSkipSeconds`，删除直播分组屏蔽字段。
- `app/src/main/java/com/tvbox/app/ui/TvBoxViewModel.kt`
  - 远程请求分支：`source-import`（URL 或粘贴内容，经 `SourceConfigFetcher` + `SubscriptionDecoder` + `SourceConfigCodec.parseImport`）、`source-home`、`source-block/unblock`、`source-delete/restore`、`source-test`、`source-categories`、`category-toggle/block/unblock`、直播源与平台站点、AI 配置。
  - 新增 `remoteCategorySourceId` / `remoteCategoryNames` 缓存最近一次获取的分类，远程页据此渲染 chips。
  - 屏蔽/恢复分类后同步过滤首页分类与影片列表，当前分类被屏蔽时回到首页并刷新。
- `app/src/main/java/com/tvbox/app/ui/TvBoxApp.kt`
  - 设置页“手机远程管理”入口与二维码弹窗；源管理页入口复用同一弹窗；保留 TV 端“屏蔽视频分类”手动管理区。
- 删除 `app/src/main/java/com/tvbox/app/data/AiConfigServer.kt`、`app/src/main/java/com/tvbox/app/data/VideoApiConfigServer.kt`（旧的 token 配置服务已被远程管理替代）。

### JSON 文件格式

```json
{
  "version": 1,
  "updatedAtMs": 1793517700459,
  "categories": ["连续剧"]
}
```

- 设备内路径：`/data/data/com.tvbox.app/files/blocked_categories.json`。
- 网页地址：`http://<电视IP>:9978/blocked-categories.json`。

### 验证（192.168.1.45 真机）

- `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain`：通过。
- 打开“手机远程管理”，地址 `http://192.168.1.45:9978/`。
- `GET /blocked-categories.json`：`200`，`{"version":1,"updatedAtMs":...,"categories":[]}`（空列表也带 `categories` 字段）。
- `POST action=source-categories id=custom_1p2y0jl`：提示“已获取“导入测试源”的分类 30 个”，页面渲染 30 个分类 chips。
- 点击“连续剧”（`POST action=category-toggle name=连续剧`）：提示“已屏蔽视频分类：连续剧”，接口返回 `["连续剧"]`，`run-as com.tvbox.app cat files/blocked_categories.json` 内容一致，chip 变为 `chip blocked`。
- 强制停止并重启应用：首页分类只剩“电影片 / 综艺片 / 动漫片”，被屏蔽的“连续剧”已去除，说明重启后仍从 JSON 文件读取。
- `POST action=category-unblock name=连续剧`：提示“已恢复视频分类：连续剧”，接口与文件均回到 `"categories": []`。

### 缺陷记录

- 时间：2026-11-01
- 症状：远程页按 URL 导入订阅时首次返回“没有可新增的源”。
- 原因：本机用于测试的 `http.server` 已退出，且 Windows 防火墙未放行 8090，电视端拉取失败。
- 修复：保持本机测试服务常驻并放行端口后，URL 导入返回“新增 1 个视频源”。
- 临时方案：无。

### Navigation

- Master doc: `devLog/README.md`
- Branch doc: `devLog/remote-manage.md`
