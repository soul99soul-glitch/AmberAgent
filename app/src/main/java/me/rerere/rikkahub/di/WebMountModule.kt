package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.agent.webmount.adapters.bilibili.BilibiliAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.bilibili.BilibiliClient
import me.rerere.rikkahub.data.agent.webmount.adapters.bilibili.BilibiliTools
import me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs.FeishuDocsAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs.FeishuDocsClient
import me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs.FeishuDocsTools
import me.rerere.rikkahub.data.agent.webmount.adapters.github.GithubAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.github.GithubClient
import me.rerere.rikkahub.data.agent.webmount.adapters.github.GithubTools
import me.rerere.rikkahub.data.agent.webmount.adapters.hackernews.HnAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.hackernews.HnClient
import me.rerere.rikkahub.data.agent.webmount.adapters.hackernews.HnTools
import me.rerere.rikkahub.data.agent.webmount.adapters.juejin.JuejinAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.juejin.JuejinClient
import me.rerere.rikkahub.data.agent.webmount.adapters.juejin.JuejinTools
import me.rerere.rikkahub.data.agent.webmount.adapters.reddit.RedditAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.reddit.RedditClient
import me.rerere.rikkahub.data.agent.webmount.adapters.reddit.RedditTools
import me.rerere.rikkahub.data.agent.webmount.adapters.zhihu.ZhihuAdapter
import me.rerere.rikkahub.data.agent.webmount.adapters.zhihu.ZhihuClient
import me.rerere.rikkahub.data.agent.webmount.adapters.zhihu.ZhihuTools
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.oauth.FeishuOAuthProvider
import me.rerere.rikkahub.data.agent.webmount.oauth.OAuthCallbackDispatcher
import me.rerere.rikkahub.data.agent.webmount.oauth.PendingOAuthStore
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthClient
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import me.rerere.rikkahub.data.agent.webmount.primitives.WebViewPool
import me.rerere.rikkahub.data.agent.webmount.profile.HostShimRegistry
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileBridge
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry
import me.rerere.rikkahub.data.agent.webmount.tools.WebMountPrimitiveTools
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSiteRegistry
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
            oauthStore = get(),
            activityStore = get(),
            appScope = get(),
        )
    }

    single { WebViewPool(appContext = get()) }

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
