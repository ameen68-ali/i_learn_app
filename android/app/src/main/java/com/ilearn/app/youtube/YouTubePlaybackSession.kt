package com.ilearn.app.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.ilearn.app.data.Lesson
import com.ilearn.app.player.LessonPlaybackSession
import com.ilearn.app.player.LessonPlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONTokener

/** No JavascriptInterface is exposed. Poll only the app-owned wrapper's bounded state. */
class YouTubePlaybackSession(private val context: Context, private val lesson: Lesson) : LessonPlaybackSession {
    private val mutable = MutableStateFlow(LessonPlaybackState(lesson.id, durationMs = lesson.durationMs))
    override val state: StateFlow<LessonPlaybackState> = mutable
    private var container: FrameLayout? = null
    private var view: WebView? = null
    private var released = false
    private val fullscreenMutable = MutableStateFlow<View?>(null)
    val fullscreen: StateFlow<View?> = fullscreenMutable
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    fun createContainerView(): FrameLayout {
        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        container = frame
        val webView = createView()
        frame.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        return frame
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun createView(): WebView {
        val videoId = requireNotNull(lesson.remoteId) { "Missing YouTube video ID" }
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        return WebView(context).apply {
            view = this
            setBackgroundColor(Color.BLACK)
            // Guard against Android 14 / Skia GLFunctorDrawable RenderThread null SkSurface crashes in Compose.
            alpha = 0.99f
            isNestedScrollingEnabled = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(customView: View, callback: CustomViewCallback) {
                    exitFullscreen()
                    fullscreenCallback = callback
                    fullscreenMutable.value = customView
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val uri = request.url
                    if (request.hasGesture() && uri.scheme == "https" && !uri.host.isNullOrBlank()) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }

                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                    val host = request.url?.host.orEmpty()
                    val path = request.url?.path.orEmpty()
                    val isPlayerResource = request.isForMainFrame ||
                        (host.contains("youtube") && (path.startsWith("/embed/") || path.contains("iframe_api")))
                    if (isPlayerResource) {
                        mutable.value = mutable.value.copy(
                            error = "تعذّر تحميل مشغّل يوتيوب. تأكد من اتصالك بالإنترنت. • YouTube needs an active internet connection.",
                            playing = false,
                            ready = false
                        )
                    }
                }
            }
            loadEmbedHtml(this, videoId)
        }
    }

    private fun loadEmbedHtml(webView: WebView, videoId: String) {
        val resumeMs = if (lesson.durationMs > 0L && lesson.lastPositionMs >= lesson.durationMs - 5000L) 0L else lesson.lastPositionMs
        val start = (resumeMs / 1000).coerceAtLeast(0)
        val origin = "https://${context.packageName}"
        val startLine = if (start > 0L) ",start:$start" else ""
        webView.loadDataWithBaseURL(
            "$origin/",
            """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
            <meta name="referrer" content="strict-origin-when-cross-origin">
            <style>html,body{margin:0;padding:0;background:#000;width:100%;height:100%;overflow:hidden}#player,iframe{width:100%;height:100%;border:0;display:block}</style>
            </head><body><div id="player"></div><script>
            var player, ready=false, failure=0;
            function onYouTubeIframeAPIReady(){
              player=new YT.Player('player',{
                height:'100%',
                width:'100%',
                videoId:'$videoId',
                playerVars:{playsinline:1,enablejsapi:1,rel:0,fs:1,origin:'$origin',widget_referrer:'$origin'$startLine},
                events:{
                  onReady:function(){ready=true;},
                  onError:function(e){failure=(e&&e.data)?e.data:5;}
                }
              });
            }
            function appState(){
              var dur=0, pos=0, vid='', playing=false;
              if(ready&&player){
                if(typeof player.getDuration==='function') dur=player.getDuration()||0;
                if(typeof player.getCurrentTime==='function') pos=player.getCurrentTime()||0;
                if(typeof player.getVideoData==='function'){ var d=player.getVideoData(); vid=(d&&d.video_id)?d.video_id:''; }
                if(typeof player.getPlayerState==='function') playing=(player.getPlayerState()===1);
              }
              return JSON.stringify({ready:ready,error:failure,position:pos,duration:dur,video:vid,playing:ready&&playing});
            }
            </script><script src="https://www.youtube.com/iframe_api" onerror="failure=1"></script></body></html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null
        )
    }

    fun retry() {
        val w = view ?: return
        val videoId = lesson.remoteId ?: return
        if (released) return
        mutable.value = LessonPlaybackState(lesson.id, durationMs = lesson.durationMs)
        loadEmbedHtml(w, videoId)
    }

    private fun js(script: String) {
        if (!released) view?.evaluateJavascript(script, null)
    }

    override fun play() = js("if(ready&&player)player.playVideo();")

    override fun pause() {
        js("if(ready&&player)player.pauseVideo();")
        mutable.value = mutable.value.copy(playing = false)
    }

    override fun seek(positionMs: Long) {
        val maxDur = state.value.durationMs.takeIf { it > 0L } ?: lesson.durationMs
        val position = if (maxDur > 0L) positionMs.coerceIn(0, maxDur) else positionMs.coerceAtLeast(0)
        js("if(ready&&player){player.seekTo(${position / 1000.0},true);}")
    }

    fun seekAndPlay(positionMs: Long) {
        val maxDur = state.value.durationMs.takeIf { it > 0L } ?: lesson.durationMs
        val position = if (maxDur > 0L) positionMs.coerceIn(0, maxDur) else positionMs.coerceAtLeast(0)
        js("if(ready&&player){player.seekTo(${position / 1000.0},true);player.playVideo();}")
    }

    fun onHostPause() {
        pause()
        if (!released) view?.onPause()
    }

    fun onHostResume() {
        if (!released) view?.onResume()
    }

    override fun sample() {
        if (released) return
        view?.evaluateJavascript("typeof appState==='function'?appState():null") { encoded ->
            if (released) return@evaluateJavascript
            runCatching {
                val raw = JSONTokener(encoded).nextValue() as? String ?: return@runCatching
                require(raw.length < 2000)
                val json = JSONObject(raw)
                val code = json.optInt("error")
                val duration = json.optDouble("duration", 0.0)
                val pos = json.optDouble("position", 0.0)
                require(duration.isFinite() && pos.isFinite() && duration in 0.0..604800.0 && pos in 0.0..604800.0)
                val loadedVideo = json.optString("video")
                val matchesLesson = loadedVideo.isEmpty() || loadedVideo == lesson.remoteId
                val reportedDurationMs = (duration * 1000).toLong()
                val effectiveDurationMs = if (reportedDurationMs > 0L) reportedDurationMs else lesson.durationMs
                mutable.value = LessonPlaybackState(
                    lessonId = lesson.id,
                    ready = json.optBoolean("ready") && code == 0 && matchesLesson,
                    playing = json.optBoolean("playing") && code == 0 && matchesLesson,
                    positionMs = (pos * 1000).toLong(),
                    durationMs = effectiveDurationMs,
                    error = if (code == 0) null else formatPlayerError(code)
                )
            }
        }
    }

    private fun formatPlayerError(code: Int): String = when (code) {
        1 -> "تعذّر تحميل مشغّل يوتيوب. تأكد من اتصالك بالإنترنت. • Failed to load YouTube player."
        100 -> "هذا الفيديو غير متاح أو خاص على يوتيوب. • Video unavailable or private."
        101, 150 -> "مالك الفيديو عطّل التضمين خارج يوتيوب. افتحه في تطبيق يوتيوب. • Embedding disabled by owner ($code)."
        152, 153 -> "رفض يوتيوب التضمين من المشغّل المدمج ($code). افتح الفيديو عبر يوتيوب. • Embed restricted ($code)."
        else -> "تعذّر تشغيل الفيديو داخل التطبيق ($code). يمكنك فتحه في يوتيوب. • YouTube cannot play this video ($code)."
    }

    fun exitFullscreen() {
        fullscreenMutable.value = null
        val callback = fullscreenCallback
        fullscreenCallback = null
        callback?.onCustomViewHidden()
    }

    override fun release() {
        if (!released) {
            pause()
            exitFullscreen()
            released = true
            val w = view
            view = null
            container?.removeAllViews()
            container = null
            if (w != null) {
                (w.parent as? ViewGroup)?.removeView(w)
                w.stopLoading()
                w.loadUrl("about:blank")
                w.onPause()
                w.removeAllViews()
                w.destroy()
            }
        }
    }
}
