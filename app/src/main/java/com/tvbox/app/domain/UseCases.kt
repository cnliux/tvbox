package com.tvbox.app.domain

/**
 * 编排层薄入口：ViewModel 只依赖这里的 useCase 与 domain 仓库接口，
 * data 实现仍由手动工厂注入。当前先覆盖首页信息流与直播取流两条高频路径。
 */
class GetHomeFeedUseCase(private val repository: MovieRepository) {
    suspend operator fun invoke(
        apiLineId: String,
        page: Int,
        typeIds: List<Int> = emptyList(),
    ): PagedMovies {
        val paged = if (typeIds.isEmpty()) {
            repository.getMovies(apiLineId, page)
        } else {
            repository.getMoviesByTypeIds(apiLineId, page, typeIds)
        }
        return paged.copy(
            movies = paged.movies.filterNot { isBlockedContent(it.name, it.typeName) },
        )
    }
}

class ResolveLiveStreamUseCase(private val repository: LiveRepository) {
    suspend operator fun invoke(): List<LiveChannel> = repository.getChannels()

    /** 数字选台：按频道号精确命中，未命中返回 null 交给 UI 走空态。 */
    fun byNumber(channels: List<LiveChannel>, number: Int): LiveChannel? =
        channels.firstOrNull { it.number == number }
}
