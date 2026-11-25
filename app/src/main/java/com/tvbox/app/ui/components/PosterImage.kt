package com.tvbox.app.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlin.math.roundToInt

/**
 * 海报尺寸档位（S5-28）：全仓海报必须走 [PosterImage] 收口，禁止直接调 AsyncImage。
 *
 * 背景：豆瓣海报源常给 500×750 甚至 1000×1500，而 TV 卡片实际只显示 ≈240×360
 * （1080p）~ 320×480（4K 栅格）。Coil 默认按 View 尺寸算，但 Compose + TV 焦点放大动画
 * （scale 1.0→1.12）会让目标尺寸在重组间抖动 ⇒ 缓存 key 漂移 ⇒ 同一张海报缓存 2 份，
 * 2GB 盒子上 30 张 1000×1500 ARGB 解码 ≈ 180MB，正是「首页滑到底返回崩/黑屏」的配方。
 *
 * 这里用**编译期固定档位**（不许第五档），配合 [PosterImage] 里的
 * `size(w,h) + precision(EXACT) + memoryCacheKey(url@tag) + bitmapConfig(RGB_565)`：
 * - 锁死解码尺寸与缓存 key，焦点动画缩放不再抖动 key；
 * - RGB_565 无 alpha 海报直接省一半内存；
 * - 内存缓存由 [ImageLoader.kt] 的 maxSizePercent 统一限流。
 */
enum class PosterKind(
    /** 缓存 key 后缀：随档位变化，避免同一 url 在不同尺寸下互相挤占。 */
    val tag: String,
    /** 逻辑尺寸（dp）。PosterImage 按当前 density 换算成像素交给 Coil。 */
    val widthDp: Dp,
    val heightDp: Dp,
) {
    /** 详情页/顶部大图（约 2:3，横向宽幅 hero 亦复用此档）。 */
    HERO("hero", 480.dp, 720.dp),

    /** 首页/搜索/历史的列表卡片（2:3 竖版海报）。 */
    CARD("card", 240.dp, 360.dp),

    /** 频道 logo（方形）。 */
    CHANNEL_LOGO("clogo", 96.dp, 96.dp),

    /** 小头像/圆形图标。 */
    AVATAR("avatar", 48.dp, 48.dp),
    ;

    /** 供内存缓存 key 使用：`url@tag`，档位不同 key 不同。 */
    fun cacheKey(url: String): String = "$url@$tag"
}

/**
 * 全仓唯一海报入口（S5-28）：把 [url] 按 [kind] 档位解码，锁死尺寸与缓存 key。
 *
 * - [ImageRequest.size] 传像素（由 density 换算）；[Precision.EXACT] 强制精确解码，
 *   防止 TV 焦点缩放动画导致目标尺寸抖动、缓存 key 漂移；
 * - [ImageRequest.memoryCacheKey] 用 `url@tag`，不同档位互不挤占；
 * - [Bitmap.Config.RGB_565]：海报无 alpha，直接省一半解码内存；
 * - crossfade 由全局 [rememberTvImageLoader] 关闭（TV 上 crossfade 双倍峰值内存）。
 */
@Composable
fun PosterImage(
    url: String,
    kind: PosterKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onError: (() -> Unit)? = null,
    /** 额外的请求定制（如防盗链 Referer/UA 头）。 */
    configure: ImageRequest.Builder.() -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imageLoader = rememberTvImageLoader()
    val widthPx = with(density) { kind.widthDp.toPx() }.roundToInt().coerceAtLeast(1)
    val heightPx = with(density) { kind.heightDp.toPx() }.roundToInt().coerceAtLeast(1)
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .size(widthPx, heightPx)
            .precision(Precision.EXACT)
            .memoryCacheKey(kind.cacheKey(url))
            .bitmapConfig(Bitmap.Config.RGB_565)
            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
            .apply(configure)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        onError = { onError?.invoke() },
        modifier = modifier,
        imageLoader = imageLoader,
    )
}
