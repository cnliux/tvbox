# 贡献指南（Contributing）

感谢你愿意为聚合视频（原 TVBox）贡献。这是一个单包多端的 Android 播放应用，
代码改动请遵循下面的约定，避免 review 反复。

## 提交前必做

1. **跑单测**：`./gradlew :app:testDebugUnitTest`（JVM）与 `cd platform_live_go && go test ./...`。
2. **跑 lint**：`./gradlew :app:lintDebug`；本项目用 `lint-baseline.xml` 基线化历史错误，**不要新增 lint 错误**。
3. **跑 TODO lint**：`./gradlew :app:lintTodo`——`TODO/FIXME/HACK` 必须带 `域-序号` 前缀（如 `TODO(P2-14)`），`P0-` 前缀会 fail。
4. **对照 PR 模板**：`.github/PULL_REQUEST_TEMPLATE.md` 的 7 个勾选框逐条确认。

## 改动约定

- **遥控器键位**：改 `RemoteKeyBindings` / 路由后，补对应单测（`RemoteKeyBindingsTest` / `LiveRemoteKeyRouterTest`），并确认键位语料（G3）通过。
- **设置持久化**：改偏好/DataStore 结构必须带 `schemaVersion` 迁移，保证旧数据可读、不静默丢弃。
- **新增依赖**：重新生成 `OSS_NOTICES`（开源许可清单），并在 PR 里说明许可证。
- **新增用户可见字符串**：进 `strings.xml`，不在 Compose 里硬编码中文。
- **用户可见行为变化**：按两段式更新 `CHANGELOG.md`（「你会感觉到 / 已知问题」）。

## 分支与提交

- 建议每个 PR 聚焦一个改动，最大 ~400 行，可独立回滚。
- 不要用 `--force` 改已合入的提交；历史是决策痕迹，别重写。

## 安全

- **不要**把密钥、token、`.env` 写进代码、日志或提交。
- 发现安全漏洞请走 `SECURITY.md` 的私密渠道，不要公开贴 0-day。

## 社区

- 提交 issue 前先搜是否已有相同问题；用 `.github/ISSUE_TEMPLATE/` 的表单（bug / 遥控键位 / 功能建议）。
- 有盒子、有真机、有遥控器怪癖？欢迎在 `docs/DEVICE_MATRIX.md` 登记你的设备，帮我们补全测试矩阵。
