package com.ilearn.app.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.*
import com.ilearn.app.data.Lesson
import com.ilearn.app.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONTokener

/** No JavascriptInterface is exposed. Poll only the app-owned wrapper's bounded state. */
class YouTubePlaybackSession(private val context: Context, private val lesson: Lesson) : LessonPlaybackSession {
    private val mutable = MutableStateFlow(LessonPlaybackState(lesson.id))
    override val state: StateFlow<LessonPlaybackState> = mutable
    private var view: WebView? = null
    private var released = false
    private val fullscreenMutable = MutableStateFlow<android.view.View?>(null)
    val fullscreen: StateFlow<android.view.View?> = fullscreenMutable
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun createView(): WebView {
        require(lesson.remoteId?.matches(Regex("[A-Za-z0-9_-]{11}")) == true)
        return WebView(context).apply {
            view = this
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = true
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(customView: android.view.View, callback: CustomViewCallback) {
                    exitFullscreen(); fullscreenCallback = callback; fullscreenMutable.value = customView
                }
                override fun onHideCustomView() { exitFullscreen() }
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
                    if (request.isForMainFrame) mutable.value = mutable.value.copy(error = "YouTube needs a working internet connection", playing = false, ready = false)
                }
            }
            val start = (lesson.lastPositionMs / 1000).coerceAtLeast(0)
            val origin = "https://${context.packageName}"
            loadDataWithBaseURL("$origin/", """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>html,body{margin:0;background:#000;width:100%;height:100%}#player{width:100%;height:100%}</style></head>
                <body><div id="player"></div><script>
                var player, ready=false, failure=0;
                function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'${lesson.remoteId}',
                  playerVars:{playsinline:1,start:$start,origin:'$origin'},
                  events:{onReady:function(){ready=true},onError:function(e){failure=e.data}}});}
                function appState(){return JSON.stringify({ready:ready,error:failure,
                  position:ready?player.getCurrentTime():0,duration:ready?player.getDuration():0,
                  video:ready?player.getVideoData().video_id:'',playing:ready&&player.getPlayerState()===1});}
                </script><script src="https://www.youtube.com/iframe_api"></script></body></html>
            """.trimIndent(), "text/html", "UTF-8", null)
        }
    }
    private fun js(script: String) { if (!released) view?.evaluateJavascript(script, null) }
    override fun play() = js("if(ready)player.playVideo();")
    override fun pause() { js("if(ready)player.pauseVideo();"); mutable.value = mutable.value.copy(playing = false) }
    override fun seek(positionMs: Long) {
        val position = if (state.value.durationMs > 0) positionMs.coerceIn(0, state.value.durationMs) else positionMs.coerceAtLeast(0)
        js("if(ready)player.seekTo(${position / 1000.0},true);")
    }
    override fun sample() {
        if (released) return
        view?.evaluateJavascript("typeof appState==='function'?appState():null") { encoded ->
            if (released) return@evaluateJavascript
            runCatching {
                val raw = JSONTokener(encoded).nextValue() as? String ?: return@runCatching
                require(raw.length < 2000)
                val json = JSONObject(raw); val code = json.optInt("error")
                val duration = json.optDouble("duration", 0.0); val pos = json.optDouble("position", 0.0)
                require(duration.isFinite() && pos.isFinite() && duration in 0.0..604800.0 && pos in 0.0..604800.0)
                val matchesLesson = json.optString("video") == lesson.remoteId && (lesson.durationMs == 0L || kotlin.math.abs(duration * 1000 - lesson.durationMs) <= 2000)
                mutable.value = LessonPlaybackState(lesson.id, json.optBoolean("ready") && code == 0 && matchesLesson, json.optBoolean("playing") && code == 0,
                    (pos * 1000).toLong(), (duration * 1000).toLong(), if (code == 0) null else "YouTube cannot play this video ($code). Open it in YouTube.")
            }
        }
    }
    fun exitFullscreen() { fullscreenMutable.value = null; val callback = fullscreenCallback; fullscreenCallback = null; callback?.onCustomViewHidden() }
    override fun release() { if (!released) { pause(); exitFullscreen(); released = true; view?.stopLoading(); view?.destroy(); view = null } }
}
