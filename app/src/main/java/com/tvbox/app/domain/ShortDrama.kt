package com.tvbox.app.domain

/** 短剧条目（来源：红果短剧网页 `category_page.recommendList`）。 */
data class ShortDrama(
    /** 剧集 ID（`series_id`，19 位数字串），用于详情与播放。 */
    val id: String,
    val title: String,
    val cover: String,
    val intro: String,
    val category: String,
    val tags: List<String>,
    val episodeCount: Int,
    /** 源里的更新状态文案，如「全102集」「更新至30集」。 */
    val remark: String,
)

/** 一页短剧列表。 */
data class ShortDramaCatalogPage(
    val items: List<ShortDrama>,
    val page: Int,
    val totalPages: Int,
)

/** 短剧详情：分集以 `vid_list` 的顺序给出（第 N 个即第 N 集）。 */
data class ShortDramaDetail(
    val drama: ShortDrama,
    val episodeIds: List<String>,
)
