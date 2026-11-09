# 平台直播每页房间数：Go 侧改造说明

## 结论

「每页房间数」不在 App 层。Kotlin 只向 Go 桥接层传 `site / categoryId / page`，每页条数由 Go 各平台解析器写死（CC 30、Showroom 30、快手 20，其余平台按上游接口一页返回）。因此本次未新增 App 设置项；要可配置必须改 Go 并重新产出 `libs/platformlive.aar`。

## 当前调用链

```
Kotlin EmbeddedPlatformLiveRepository.getRooms(site, categoryId, page)
  → platformlive.Platformlive.roomsJson(site, categoryId, page.toString())
  → Go platformlive.RoomsJson(site, categoryID, page)        // 只解析页码
  → Service.Rooms(site, categoryID, page)                    // service.go:126
  → Resolver.GetCategoryRooms(categoryID, page)              // models.go:102
```

写死位置：

| 平台 | 文件 | 每页条数 |
|---|---|---|
| CC | `platform_live_go/cc.go` | `ccPageSize = 30`（`offset`/`size` 请求参数） |
| Showroom | `platform_live_go/showroom.go` | `showroomPageSize = 30`（对整表本地切片） |
| 快手 | `platform_live_go/kuaishou.go` | 请求参数 `pageSize=20` 字面量 |
| 其余平台 | `douyu/huya/bilibili/...` | 由上游接口单页决定，无本地常量 |

服务端入口 `cmd/platform-live-server/main.go` 与 `handlers.go` 也只透传 page，需与桥接层同步改。

## Go 侧需要改什么

1. 在 `models.go` 的 `Resolver` 接口为 `GetCategoryRooms` 增加每页条数参数，例如：

```go
GetCategoryRooms(categoryID string, page int64, pageSize int64) (LiveRoomsPage, error)
```

所有平台实现与 `flv_proxy_test.go` 的 fake 同步修改；不支持自定义条数的平台忽略该参数。

2. `service.go` 的 `Service.Rooms` 增加 `pageSize` 透传；`platformlive.go` 增加新导出入口（保留旧入口避免 AAR 破坏性变更）：

```go
func RoomsJsonWithPageSize(site, categoryID, page, pageSize string) (string, error)
```

旧 `RoomsJson` 内部调用新入口，`pageSize` 传空/0 表示沿用各平台原默认值（行为不变）。

3. 三个写死点的处理：
   - CC：`ccPageSize` 改为按入参计算 `offset = (page-1)*pageSize`、`size = pageSize`，并在返回条数判断处使用 `pageSize`。
   - Showroom：`showroomPageSize` 改为入参；`pageCount` 与 `start/end` 用同一值。
   - 快手：把 `pageSize=20` 换成入参。
   - 其余平台：忽略入参即可（返回上游单页）。

4. `cmd/platform-live-server` 的 `/rooms` 接口增加可选 `pageSize` 查询参数，保持与 APK 内置桥接一致。

5. 重新绑定 AAR：

```bash
cd platform_live_go
gomobile bind -target=android -o ../app/libs/platformlive.aar ./...
```

之后构建不要再加 `-PtvboxSkipPlatformBind`（该参数会跳过绑定）。

## App 侧已留好的入口

- `PlatformLiveRepository.getRooms(site, categoryId, page)`：唯一数据入口，后续加 `pageSize: Int? = null` 即可向下透传。
- `EmbeddedPlatformLiveRepository.getRooms`：调用 `Platformlive.roomsJson(...)` 的唯一位置。
- `PlatformLiveUiState.roomPage / roomPageCount`：分页状态已集中，翻页与「没有更多」判断都读这两项。
- `TvBoxViewModel.loadPlatformLiveRooms / loadMorePlatformLiveRooms`：页码递增与页数封顶逻辑已集中在这两个方法。
- `Settings` 体系：后续若加 `AppSettings.platformLiveRoomsPerPage`，只需在 `AppSettingsRepository` 与双端设置页补一项，消费点直接读状态。

## 建议

若只是想让用户「一次多拿一些房间」，改 Go 的 `pageSize` 成本较高；更轻的做法是保持 Go 不动，在 `TvBoxViewModel.loadMorePlatformLiveRooms` 里连续请求 N 页合并（设置项语义为「每次加载页数」，默认 1 不改变行为）。该方案不需要重编 AAR，但属于本轮范围之外的独立需求，未实施。
