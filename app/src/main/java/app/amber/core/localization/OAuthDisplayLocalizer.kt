package app.amber.core.localization

import android.content.Context
import app.amber.ai.provider.providers.google.GoogleGeminiOAuthCopy
import app.amber.agent.R
import app.amber.agent.data.workspace.ArtifactLocalizedCopy
import app.amber.common.oauth.LoopbackOAuthCopy
import app.amber.feature.webmount.oauth.OAuthProviderErrorCopy

/** Resolves copy that is consumed by non-Compose OAuth and workspace paths. */
object OAuthDisplayLocalizer {
    fun loopback(context: Context): LoopbackOAuthCopy = LoopbackOAuthCopy(
        successMessage = context.getString(R.string.oauth_loopback_success_message),
        failureMessage = { reason ->
            context.getString(R.string.oauth_loopback_failure_message, reason)
        },
        notFoundMessage = context.getString(R.string.oauth_loopback_not_found),
        bindFailure = context.getString(R.string.oauth_loopback_bind_failed),
        serverClosed = context.getString(R.string.oauth_loopback_server_closed),
        requestLineEmpty = context.getString(R.string.oauth_loopback_request_line_empty),
        headersUnterminated = context.getString(R.string.oauth_loopback_headers_unterminated),
        requestTooLarge = context.getString(R.string.oauth_loopback_request_too_large),
        invalidRequest = context.getString(R.string.oauth_loopback_invalid_request),
        waitingForCallback = context.getString(R.string.oauth_loopback_waiting_callback),
        missingQuery = context.getString(R.string.oauth_loopback_missing_query),
        requestLineUnterminated = context.getString(R.string.oauth_loopback_request_line_unterminated),
    )

    fun oauthProviderErrors(context: Context): OAuthProviderErrorCopy = OAuthProviderErrorCopy(
        tokenEndpointFailed = { statusCode, body ->
            context.getString(
                R.string.setting_webmount_oauth_feishu_token_failed,
                statusCode,
                body,
            )
        },
        oauthError = { code, message ->
            context.getString(R.string.setting_webmount_oauth_feishu_error, code, message)
        },
        missingAccessToken = { body ->
            context.getString(R.string.setting_webmount_oauth_feishu_missing_access_token, body)
        },
    )

    fun googleGeminiOAuth(context: Context): GoogleGeminiOAuthCopy = GoogleGeminiOAuthCopy(
        callbackTimedOut = { timeoutSeconds ->
            context.getString(
                R.string.setting_provider_page_gemini_oauth_callback_timeout,
                timeoutSeconds,
            )
        },
        callbackFailed = { reason ->
            context.getString(R.string.setting_provider_page_gemini_oauth_callback_failed, reason)
        },
        stateMismatch = { receivedState, expectedState ->
            context.getString(
                R.string.setting_provider_page_gemini_oauth_state_mismatch,
                receivedState,
                expectedState,
            )
        },
        missingCode = context.getString(R.string.setting_provider_page_gemini_oauth_callback_missing_code),
        refreshTokenMissing = context.getString(R.string.setting_provider_page_gemini_oauth_refresh_token_missing),
        refreshFailed = { statusCode ->
            context.getString(R.string.setting_provider_page_gemini_oauth_refresh_failed, statusCode)
        },
        refreshAccessTokenMissing =
            context.getString(R.string.setting_provider_page_gemini_oauth_refresh_access_token_missing),
        sessionMissing = context.getString(R.string.setting_provider_page_gemini_oauth_session_missing),
        accessTokenEmpty = context.getString(R.string.setting_provider_page_gemini_oauth_access_token_empty),
        tokenUnavailable = context.getString(R.string.setting_provider_page_gemini_oauth_token_unavailable),
        tokenRefreshFailed = context.getString(R.string.setting_provider_page_gemini_oauth_token_refresh_failed),
        projectMissing = context.getString(R.string.setting_provider_page_gemini_oauth_project_missing),
        onboardingRequired = context.getString(R.string.setting_provider_page_gemini_oauth_onboarding_required),
        tokenExpired = context.getString(R.string.setting_provider_page_gemini_oauth_token_expired),
        sessionUnavailable = { status ->
            context.getString(R.string.setting_provider_page_gemini_oauth_session_unavailable, status)
        },
        clientUnavailable = context.getString(R.string.setting_provider_page_gemini_oauth_client_unavailable),
        onboardingSessionMissing =
            context.getString(R.string.setting_provider_page_gemini_oauth_onboarding_session_missing),
        onboardingProjectMissing = { response ->
            context.getString(R.string.setting_provider_page_gemini_oauth_onboarding_project_missing, response)
        },
        onboardingOperationFailed = { response ->
            context.getString(R.string.setting_provider_page_gemini_oauth_onboarding_operation_failed, response)
        },
        onboardingTimedOut = { timeoutSeconds ->
            context.getString(R.string.setting_provider_page_gemini_oauth_onboarding_timed_out, timeoutSeconds)
        },
        cloudRequestFailed = { method, statusCode, body ->
            context.getString(
                R.string.setting_provider_page_gemini_oauth_cloud_request_failed,
                method,
                statusCode,
                body,
            )
        },
        cloudResponseInvalid = { method, body ->
            context.getString(R.string.setting_provider_page_gemini_oauth_cloud_response_invalid, method, body)
        },
        cloudPollFailed = { statusCode, body ->
            context.getString(R.string.setting_provider_page_gemini_oauth_cloud_poll_failed, statusCode, body)
        },
        cloudPollResponseInvalid = { body ->
            context.getString(R.string.setting_provider_page_gemini_oauth_cloud_poll_response_invalid, body)
        },
        tokenExchangeFailed = { statusCode ->
            context.getString(R.string.setting_provider_page_gemini_oauth_token_exchange_failed, statusCode)
        },
        tokenExchangeAccessTokenMissing =
            context.getString(R.string.setting_provider_page_gemini_oauth_token_exchange_access_token_missing),
    )

    fun artifact(context: Context): ArtifactLocalizedCopy = ArtifactLocalizedCopy(
        imageLabel = context.getString(R.string.workspace_artifact_image_label),
        videoLabel = context.getString(R.string.workspace_artifact_video_label),
        audioLabel = context.getString(R.string.workspace_artifact_audio_label),
        reasoningBlock = context.getString(R.string.workspace_artifact_reasoning_block),
        unnamedMessage = context.getString(R.string.workspace_artifact_unnamed_message),
        editedFrom = { source ->
            context.getString(R.string.workspace_artifact_edited_from, source)
        },
    )
}
