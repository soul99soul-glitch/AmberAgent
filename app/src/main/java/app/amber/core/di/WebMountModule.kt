package app.amber.core.di

import app.amber.feature.webmount.adapters.bilibili.BilibiliAdapter
import app.amber.feature.webmount.adapters.bilibili.BilibiliClient
import app.amber.feature.webmount.adapters.bilibili.BilibiliTools
import app.amber.feature.webmount.adapters.feishudocs.FeishuDocsAdapter
import app.amber.feature.webmount.adapters.feishudocs.FeishuDocsClient
import app.amber.feature.webmount.adapters.feishudocs.FeishuDocsTools
import app.amber.feature.webmount.adapters.github.GithubAdapter
import app.amber.feature.webmount.adapters.github.GithubClient
import app.amber.feature.webmount.adapters.github.GithubTools
import app.amber.feature.webmount.adapters.hackernews.HnAdapter
import app.amber.feature.webmount.adapters.hackernews.HnClient
import app.amber.feature.webmount.adapters.hackernews.HnTools
import app.amber.feature.webmount.adapters.juejin.JuejinAdapter
import app.amber.feature.webmount.adapters.juejin.JuejinClient
import app.amber.feature.webmount.adapters.juejin.JuejinTools
import app.amber.feature.webmount.adapters.reddit.RedditAdapter
import app.amber.feature.webmount.adapters.reddit.RedditClient
import app.amber.feature.webmount.adapters.reddit.RedditTools
import app.amber.feature.webmount.adapters.zhihu.ZhihuAdapter
import app.amber.feature.webmount.adapters.zhihu.ZhihuClient
import app.amber.feature.webmount.adapters.zhihu.ZhihuTools
import app.amber.feature.webmount.cookie.WebMountCookieProvider
import app.amber.feature.webmount.core.WebMountAdapter
import app.amber.feature.webmount.core.WebMountManager
import app.amber.feature.webmount.oauth.FeishuOAuthProvider
import app.amber.feature.webmount.oauth.OAuthCallbackDispatcher
import app.amber.feature.webmount.oauth.PendingOAuthStore
import app.amber.feature.webmount.oauth.WebMountOAuthClient
import app.amber.feature.webmount.oauth.WebMountOAuthTokenStore
import app.amber.feature.webmount.primitives.WebViewPool
import app.amber.feature.webmount.profile.HostShimRegistry
import app.amber.feature.webmount.profile.ProfileBridge
import app.amber.feature.webmount.profile.ProfileRegistry
import app.amber.feature.webmount.tools.WebMountPageSnapshotCache
import app.amber.feature.webmount.tools.WebMountPrimitiveTools
import app.amber.feature.webmount.usersites.UserSiteRegistry
import org.koin.dsl.module

/**
 * WebMount Stations (experimental, Phase 1) Koin module — independent from
 * the iCloud experimental feature, hosts the inline-browser-backed adapters
 * (Hacker News, Reddit, Juejin, Feishu Docs, GitHub, Bilibili, Zhihu) plus
 * the OAuth dispatch / cookie / profile / primitive-tool infrastructure.
 *
 * Extracted from AppModule in M1.5 continuation.
 */
val webMountModule = module {
    single { WebMountCookieProvider() }

    single { WebMountOAuthTokenStore(context = get()) }

    single { PendingOAuthStore(context = get()) }

    single { OAuthCallbackDispatcher() }

    // Phase 2 M2.0.3 fix: createdAtStart so the OAuth resume collector
    // subscribes to dispatcher.events BEFORE a cold-start callback Intent
    // can arrive. Combined with OAuthCallbackDispatcher's replay=1 this
    // closes the race where the dispatch fires before any UI/service has
    // resolved this singleton.
    single(createdAtStart = true) {
        WebMountOAuthClient(
            context = get(),
            store = get(),
            pendingStore = get(),
            dispatcher = get(),
            http = get(),
            appScope = get(),
        ).apply {
            register(FeishuOAuthProvider)
        }
    }

    single { HnClient(http = get()) }
    single { HnTools(client = get()) }
    single { HnAdapter(tools = get()) }

    single { RedditClient(http = get()) }
    single { RedditTools(client = get()) }
    single { RedditAdapter(tools = get()) }

    single { JuejinClient(http = get()) }
    single { JuejinTools(client = get()) }
    single { JuejinAdapter(tools = get(), cookieProvider = get()) }

    single { FeishuDocsClient(http = get()) }
    single { FeishuDocsTools(client = get(), pool = get()) }
    single { FeishuDocsAdapter(tools = get(), oauthClient = get()) }

    single { GithubClient(http = get()) }
    single { GithubTools(client = get()) }
    single { GithubAdapter(tools = get(), oauthStore = get()) }

    single { BilibiliClient(http = get()) }
    single { BilibiliTools(client = get()) }
    single { BilibiliAdapter(tools = get(), cookieProvider = get()) }

    single { ZhihuClient(http = get()) }
    single { ZhihuTools(client = get()) }
    single { ZhihuAdapter(tools = get(), cookieProvider = get()) }

    single {
        val adapters: List<WebMountAdapter> = listOf(
            get<HnAdapter>(),
            get<RedditAdapter>(),
            get<JuejinAdapter>(),
            get<FeishuDocsAdapter>(),
            get<GithubAdapter>(),
            get<BilibiliAdapter>(),
            get<ZhihuAdapter>(),
        )
        WebMountManager(
            context = get(),
            adapters = adapters,
            cookieProvider = get(),
            activityStore = get(),
            appScope = get(),
        )
    }

    single {
        WebViewPool(
            appContext = get(),
            onSessionDestroyed = WebMountPageSnapshotCache::invalidate,
        )
    }

    single { ProfileRegistry(context = get()) }

    single { UserSiteRegistry(context = get()) }

    single { HostShimRegistry(context = get()) }

    single { ProfileBridge(shimRegistry = get()) }

    single {
        WebMountPrimitiveTools(
            pool = get(),
            activityStore = get(),
            manager = get(),
            profileRegistry = get(),
            cookieProvider = get(),
            profileBridge = get(),
            userSiteRegistry = get(),
            oauthStore = get(),
        )
    }
}
