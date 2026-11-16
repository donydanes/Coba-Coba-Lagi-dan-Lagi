package dev.framegen

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.*
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.os.Looper
import android.view.*
import android.widget.Toast
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class FrameGenService : Service() {
    companion object {
        const val ACTION_STOP = "dev.framegen.STOP"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var overlay: SurfaceView? = null
    private var panel: Panel? = null
    private var renderer: Renderer? = null
    private var ovLp: WindowManager.LayoutParams? = null
    private var ovWm: WindowManager? = null
    private var probe: FpsProbe? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startForegroundNotif()
        val data = i?.getParcelableExtra("data", Intent::class.java)
        if (i == null || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            begin(i.getIntExtra("code", 0), data)
            running = true
            refreshTile()
        } catch (e: Throwable) {
            toast("Gagal mulai: " + e.message)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fg", "Frame Gen", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FrameGenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "fg")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Frame Gen aktif")
            .setContentText("Ketuk untuk berhenti")
            .setContentIntent(stop)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun begin(code: Int, data: Intent) {
        FgState.load(this)
        // overlay opaque (Android bisa melewati layer game di bawahnya); tidak dipakai bila ada zona tembus
        val opaque = FgState.opaquePref && FgState.holesL.isEmpty() && FgState.holesP.isEmpty()
        FgState.opaque = opaque
        val a11y = FgAccessibilityService.instance
            ?: throw IllegalStateException("Aktifkan Accessibility FrameGen dulu")
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width > 0 && height > 0) { FgState.contentW = width; FgState.contentH = height }
            }
        }, main)

        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        FgState.contentW = b.width()
        FgState.contentH = b.height()
        val dpi = resources.displayMetrics.densityDpi

        val sv = SurfaceView(this)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(if (opaque) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                if (FgState.fullScreen && !CaptureExclude.excludeSurfaceView(sv)) {
                    main.post { toast("Overlay tidak bisa dikecualikan (" + CaptureExclude.lastError + "). Pakai mode Satu aplikasi atau tombol Buka API tersembunyi."); stopSelf() }
                    return
                }
                renderer?.quit()
                hintFrameRate(h.surface)
                val r = Renderer(
                    this@FrameGenService, h.surface, mp, dpi, opaque,
                    { v -> setOverlayVisible(v) },
                    { w, hh -> main.post { sv.holder.setFixedSize(w, hh) } } // buffer overlay = resolusi proses
                ) { msg ->
                    main.post { toast(msg); stopSelf() }
                }
                renderer = r
                r.start()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { renderer?.quit(); renderer = null }
        })
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        ovLp = lp
        ovWm = a11y.wm
        a11y.wm.addView(sv, lp)
        overlay = sv

        // panel ditambah setelah overlay supaya berada di atasnya
        panel = Panel(a11y, a11y.wm).also { it.show() }
        FgState.sfFps = 0f
        probe = FpsProbe().also { it.start() }
    }

    /** Mode overlay opaque: jeda = sembunyikan overlay lewat alpha jendela (bukan menggambar transparan). */
    private fun setOverlayVisible(v: Boolean) {
        main.post {
            val sv = overlay ?: return@post
            val lp = ovLp ?: return@post
            lp.alpha = if (v) 1f else 0f
            runCatching { ovWm?.updateViewLayout(sv, lp) }
        }
    }

    /** Beri tahu sistem refresh rate tertinggi pada resolusi layar saat ini untuk permukaan overlay. */
    private fun hintFrameRate(s: Surface) {
        runCatching {
            val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val cur = d.mode
            var hz = d.refreshRate
            for (m in d.supportedModes) {
                if (m.physicalWidth == cur.physicalWidth && m.physicalHeight == cur.physicalHeight) hz = maxOf(hz, m.refreshRate)
            }
            s.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    override fun onDestroy() {
        running = false
        FgState.opaque = false
        val appCtx = applicationContext
        Thread { Projection.restore(appCtx) }.start()
        refreshTile()
        panel?.hide(); panel = null
        overlay?.let { v -> runCatching { FgAccessibilityService.instance?.wm?.removeView(v) } }
        overlay = null
        probe?.quit(); probe = null
        renderer?.quit(); renderer = null
        projection?.stop(); projection = null
        super.onDestroy()
    }

    private fun refreshTile() {
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, FgTileService::class.java)
            )
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------- util
private fun snap(x: Float) = maxOf(16, (x / 16).toInt() * 16)
private fun wantSize() = intArrayOf(snap(FgState.contentW * FgState.scale), snap(FgState.contentH * FgState.scale))
private fun mvDim(x: Int, k: Float) = maxOf(1, ceil(x / (16f * k)).toInt())
private fun mvDimF(x: Int, k: Float) = maxOf(1, ceil(x / (8f * k)).toInt())

// ---------------------------------------------------------------- shaders
private const val VS = """#version 300 es
out vec2 vUv;
void main(){
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

private const val FS_COPY = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uOes;
uniform mat4 uTex;
uniform float uSharp;
uniform vec2 uTexel;
in vec2 vUv; out vec4 o;
void main(){
  vec2 uv = (uTex * vec4(vUv, 0.0, 1.0)).xy;
  vec3 c = texture(uOes, uv).rgb;
  if(uSharp > 0.001){
    vec3 n = texture(uOes, uv + vec2(0.0, -uTexel.y)).rgb;
    vec3 s = texture(uOes, uv + vec2(0.0, uTexel.y)).rgb;
    vec3 w = texture(uOes, uv + vec2(-uTexel.x, 0.0)).rgb;
    vec3 e = texture(uOes, uv + vec2(uTexel.x, 0.0)).rgb;
    vec3 mn = min(c, min(min(n, s), min(w, e)));
    vec3 mx = max(c, max(max(n, s), max(w, e)));
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.001)), 0.0, 1.0));
    vec3 wgt = amp * (-1.0 / mix(8.0, 5.0, uSharp));
    c = clamp((c + (n + s + w + e) * wgt) / (1.0 + 4.0 * wgt), 0.0, 1.0);
  }
  o = vec4(c, 1.0);
}"""

private const val FS_BLIT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform vec4 uH[8];
uniform int uHn;
in vec2 vUv; out vec4 o;
void main(){
  for(int i = 0; i < 8; i++){
    if(i < uHn){
      vec4 z = uH[i];
      if(vUv.x >= z.x && vUv.x <= z.z && vUv.y >= z.y && vUv.y <= z.w){ o = vec4(0.0); return; }
    }
  }
  o = vec4(texture(uTex, vUv).rgb, 1.0);
}"""

// Selisih rata-rata |cur-prev| per sel 64x64; mipmap level terakhir = rata-rata seluruh layar (deteksi pergantian adegan).
private const val FS_DIFF = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
in vec2 vUv; out vec4 o;
float L(sampler2D s, vec2 p){ return dot(texture(s, p).rgb, vec3(0.299, 0.587, 0.114)); }
void main(){
  float s = 0.0;
  for(int j = 0; j < 4; j++){
    for(int i = 0; i < 4; i++){
      vec2 p = vUv + (vec2(float(i), float(j)) - 1.5) * 0.25 / 64.0;
      s += abs(L(uCur, p) - L(uPrev, p));
    }
  }
  o = vec4(s / 16.0, 0.0, 0.0, 1.0);
}"""

// Block matching. Blok = 16*uK piksel, langkah cari = 2*uK piksel, jangkauan +-8*uK piksel.
private const val FS_ME = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform ivec2 uSize;
uniform float uK;
out vec4 o;
ivec2 cl(ivec2 p){ return clamp(p, ivec2(0), uSize - 1); }
float lum(sampler2D s, ivec2 p){ return dot(texelFetch(s, cl(p), 0).rgb, vec3(0.299, 0.587, 0.114)); }
ivec2 offs(int k){ return ivec2(vec2(float((k & 3) * 4 + 2), float((k >> 2) * 4 + 2)) * uK); }
void main(){
  float B = 16.0 * uK;
  ivec2 ob = ivec2(vec2(ivec2(gl_FragCoord.xy)) * B);
  float c[16];
  for(int k = 0; k < 16; k++){ c[k] = lum(uCur, ob + offs(k)); }
  float best = 1e9;
  ivec2 bd = ivec2(0);
  for(int dy = -4; dy <= 4; dy++){
    for(int dx = -4; dx <= 4; dx++){
      ivec2 d = ivec2(vec2(float(dx), float(dy)) * 2.0 * uK);
      float s = 0.0;
      for(int k = 0; k < 16; k++){ s += abs(c[k] - lum(uPrev, ob + offs(k) + d)); }
      s += 0.01 * float(abs(dx) + abs(dy));
      if(s < best){ best = s; bd = d; }
    }
  }
  o = vec4(vec2(bd) / B + 0.5, clamp(best / 16.0, 0.0, 1.0), 1.0);
}"""

// Penyempurnaan: blok 8*K px, mulai dari vektor kasar, langkah 0.5*K px (sub-piksel lewat filter linear).
private const val FS_ME_B = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uMvA;
uniform vec2 uSize;
uniform float uK;
out vec4 o;
float lumF(sampler2D s, vec2 p){ return dot(texture(s, (p + 0.5) / uSize).rgb, vec3(0.299, 0.587, 0.114)); }
vec2 offs(int k){ return vec2(float((k & 3) * 2 + 1), float((k >> 2) * 2 + 1)) * uK; }
void main(){
  ivec2 bi = ivec2(gl_FragCoord.xy);
  vec2 ob = vec2(bi) * 8.0 * uK;
  ivec2 pa = clamp(bi / 2, ivec2(0), textureSize(uMvA, 0) - 1);
  vec2 par = (texelFetch(uMvA, pa, 0).rg - 0.5) * 16.0 * uK;
  float c[16];
  for(int k = 0; k < 16; k++){ c[k] = lumF(uCur, ob + offs(k)); }
  float best = 1e9;
  vec2 bd = par;
  for(int dy = -3; dy <= 3; dy++){
    for(int dx = -3; dx <= 3; dx++){
      vec2 d = par + vec2(float(dx), float(dy)) * 0.5 * uK;
      float s = 0.0;
      for(int k = 0; k < 16; k++){ s += abs(c[k] - lumF(uPrev, ob + offs(k) + d)); }
      s += 0.004 * float(abs(dx) + abs(dy));
      if(s < best){ best = s; bd = d; }
    }
  }
  o = vec4(bd / (24.0 * uK) + 0.5, clamp(best / 16.0, 0.0, 1.0), 1.0);
}"""

// Pass padat per piksel: pilih vektor terbaik dari blok sendiri, nol, dan 4 blok tetangga (galat warna pada t=0.5).
private const val FS_DENSE = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uMvB;
uniform vec2 uSize;
uniform vec2 uDS;
uniform float uK;
out vec4 o;
vec2 mvAt(ivec2 b){
  b = clamp(b, ivec2(0), textureSize(uMvB, 0) - 1);
  return (texelFetch(uMvB, b, 0).rg - 0.5) * 24.0 * uK;
}
void main(){
  vec2 uv = gl_FragCoord.xy / uDS;
  ivec2 b = ivec2(floor(uv * uSize / (8.0 * uK)));
  vec2 cand[6];
  cand[0] = mvAt(b);
  cand[1] = vec2(0.0);
  cand[2] = mvAt(b + ivec2(1, 0));
  cand[3] = mvAt(b + ivec2(-1, 0));
  cand[4] = mvAt(b + ivec2(0, 1));
  cand[5] = mvAt(b + ivec2(0, -1));
  vec2 bestD = vec2(0.0);
  float bestE = 1e9;
  for(int k = 0; k < 6; k++){
    vec2 d = cand[k];
    if(k > 1 && d == cand[0]) continue;
    vec2 du = d / uSize;
    vec3 df = abs(texture(uPrev, uv + du * 0.5).rgb - texture(uCur, uv - du * 0.5).rgb);
    float e = df.r + df.g + df.b + (k == 0 ? 0.0 : (k == 1 ? 0.03 : 0.05));
    if(e < bestE){ bestE = e; bestD = d; }
  }
  o = vec4(bestD / (24.0 * uK) + 0.5, clamp(bestE / 3.0, 0.0, 1.0), 1.0);
}"""

// Frame perantara: vektor padat per piksel. Zona tembus = kosong, zona salin = frame asli,
// pergantian adegan (selisih global besar) = tahan frame lama.
private const val FS_INTERP = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uMv;
uniform sampler2D uDiff;
uniform vec2 uSize;
uniform float uT;
uniform float uK;
uniform vec4 uZ[8];
uniform int uZn;
uniform vec4 uH[8];
uniform int uHn;
in vec2 vUv; out vec4 o;
void main(){
  for(int i = 0; i < 8; i++){
    if(i < uHn){
      vec4 z = uH[i];
      if(vUv.x >= z.x && vUv.x <= z.z && vUv.y >= z.y && vUv.y <= z.w){ o = vec4(0.0); return; }
    }
  }
  float cut = smoothstep(0.10, 0.20, textureLod(uDiff, vec2(0.5), 6.0).r);
  if(cut > 0.999){ o = vec4(texture(uPrev, vUv).rgb, 1.0); return; }
  for(int i = 0; i < 8; i++){
    if(i < uZn){
      vec4 z = uZ[i];
      if(vUv.x >= z.x && vUv.x <= z.z && vUv.y >= z.y && vUv.y <= z.w){ o = vec4(texture(uCur, vUv).rgb, 1.0); return; }
    }
  }
  vec4 m = texture(uMv, vUv);
  vec2 d = (m.rg - 0.5) * 24.0 * uK / uSize;
  vec3 fb = texture(uCur, vUv).rgb;
  vec2 dp = d * uSize;
  if(cut < 0.001 && dot(dp, dp) < 0.0625 && m.b < 0.12){ o = vec4(fb, 1.0); return; }
  mediump vec3 a = texture(uPrev, vUv + d * uT).rgb;
  mediump vec3 c = texture(uCur, vUv - d * (1.0 - uT)).rgb;
  float occ = max(smoothstep(0.10, 0.30, length(a - c)), smoothstep(0.12, 0.35, m.b));
  vec3 w = mix(mix(a, c, uT), fb, occ);
  if(cut > 0.001){ w = mix(w, texture(uPrev, vUv).rgb, cut); }
  o = vec4(w, 1.0);
}"""

// ---------------------------------------------------------------- renderer
private class Tgt(val w: Int, val h: Int, filter: Int, levels: Int = 1) {
    val tex: Int
    val fbo: Int
    init {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        tex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        // penyimpanan immutable: memungkinkan GPU Mali memakai kompresi framebuffer (AFBC) -> bandwidth lebih hemat
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
    }
    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

private class Renderer(
    val ctx: Context, val surface: Surface, val mp: MediaProjection,
    val dpi: Int, val opaque: Boolean, val setVisible: (Boolean) -> Unit, val onOut: (Int, Int) -> Unit,
    val onFail: (String) -> Unit
) : Thread("fg-render") {
    @Volatile private var alive = true
    private val q = LinkedBlockingQueue<Long>()

    fun quit() {
        alive = false
        q.offer(0L)
        if (currentThread() !== this) join(1500)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader gagal: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun prog(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VS))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link gagal: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    /** Tunggu sampai nanoTime >= t. Mengembalikan true bila frame tangkapan baru sudah tiba (sisa jadwal dibatalkan). */
    private fun waitUntil(t: Long): Boolean {
        while (alive) {
            if (q.peek() != null) return true
            val rem = t - System.nanoTime()
            if (rem <= 0L) return false
            if (rem > 2_000_000L) java.util.concurrent.locks.LockSupport.parkNanos(rem - 1_500_000L) else Thread.yield()
        }
        return true
    }

    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ec: EGLContext = EGL14.EGL_NO_CONTEXT
        var es: EGLSurface = EGL14.EGL_NO_SURFACE
        var vd: VirtualDisplay? = null
        var st: SurfaceTexture? = null
        var cs: Surface? = null
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(dpy, ver, 0, ver, 1)
            val cfgAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0)
            val cfg = cfgs[0] ?: throw RuntimeException("EGL config OpenGL ES 3 tidak ada")
            ec = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, 0x3100, 0x3101, EGL14.EGL_NONE), 0)
            if (ec == EGL14.EGL_NO_CONTEXT) {
                ec = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            }
            es = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, es, es, ec)
            GLES30.glDisable(GLES30.GL_DITHER)
            EGL14.eglSwapInterval(dpy, 0) // pacing diatur sendiri lewat waktu, bukan vsync

            val pCopy = prog(FS_COPY)
            val pMe = prog(FS_ME)
            val pMeB = prog(FS_ME_B)
            val pDiff = prog(FS_DIFF)
            val pDense = prog(FS_DENSE)
            val pInt = prog(FS_INTERP)
            val pBlit = prog(FS_BLIT)

            // timer GPU (GL_EXT_disjoint_timer_query), hanya diukur saat grafik di panel terbuka
            var tq = (GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: "").contains("GL_EXT_disjoint_timer_query")
            FgState.gpuOk = tq
            val qid = IntArray(4)
            if (tq) GLES30.glGenQueries(4, qid, 0)
            val qPend = BooleanArray(4)
            val qOn = BooleanArray(4)
            val qv = IntArray(1)
            val dj = IntArray(1)
            fun qBegin(i: Int) {
                if (!tq || !FgState.graph) return
                if (qPend[i]) {
                    GLES30.glGetQueryObjectuiv(qid[i], GLES30.GL_QUERY_RESULT_AVAILABLE, qv, 0)
                    if (qv[0] != 0) {
                        GLES30.glGetQueryObjectuiv(qid[i], GLES30.GL_QUERY_RESULT, qv, 0)
                        GLES30.glGetIntegerv(0x8FBB, dj, 0) // GL_GPU_DISJOINT_EXT
                        if (dj[0] == 0) {
                            val ms = (qv[0].toLong() and 0xFFFFFFFFL) / 1e6f
                            FgState.gpuMs[i] = FgState.gpuMs[i] * 0.8f + ms * 0.2f
                        }
                        qPend[i] = false
                    }
                }
                if (!qPend[i]) {
                    GLES30.glGetError()
                    GLES30.glBeginQuery(0x88BF, qid[i]) // GL_TIME_ELAPSED_EXT
                    if (GLES30.glGetError() != 0) { tq = false; FgState.gpuOk = false } else qOn[i] = true
                }
            }
            fun qEnd(i: Int) {
                if (qOn[i]) { GLES30.glEndQuery(0x88BF); qOn[i] = false; qPend[i] = true }
            }

            val ot = IntArray(1)
            GLES30.glGenTextures(1, ot, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ot[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val w0 = wantSize()
            var cw = w0[0]
            var ch = w0[1]
            var scaleUsed = FgState.scale
            var k = scaleUsed / 0.5f
            onOut(cw, ch)

            val stl = SurfaceTexture(ot[0])
            st = stl
            stl.setDefaultBufferSize(cw, ch)
            stl.setOnFrameAvailableListener { q.offer(1L) }
            val capSurface = Surface(stl)
            cs = capSurface

            // Resolusi gambar (cw x ch) = pilihan slider. Gerak dihitung di resolusi tetap maks 50% (lw x lh),
            // jadi di 100% gambar tetap tajam tetapi bebannya mendekati 50%.
            var useLow = scaleUsed > 0.5f
            var lw = if (useLow) snap(FgState.contentW * 0.5f) else cw
            var lh = if (useLow) snap(FgState.contentH * 0.5f) else ch
            var kM = if (useLow) 1f else k
            var prev = Tgt(cw, ch, GLES30.GL_LINEAR)
            var cur = Tgt(cw, ch, GLES30.GL_LINEAR)
            var prevL = Tgt(lw, lh, GLES30.GL_LINEAR)
            var curL = Tgt(lw, lh, GLES30.GL_LINEAR)
            var mv = Tgt(mvDim(lw, kM), mvDim(lh, kM), GLES30.GL_NEAREST)
            var mvB = Tgt(mvDimF(lw, kM), mvDimF(lh, kM), GLES30.GL_LINEAR)
            var dense = Tgt(lw / 2, lh / 2, GLES30.GL_NEAREST)
            val diff = Tgt(64, 64, GLES30.GL_LINEAR, 7)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, diff.tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_NEAREST)

            // createVirtualDisplay hanya boleh sekali per MediaProjection (Android 14+); ukuran diubah lewat resize()
            val vdl = mp.createVirtualDisplay(
                "FrameGen", cw, ch, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, capSurface, null, null
            )
            vd = vdl

            val dm = ctx.getSystemService(DisplayManager::class.java)
            var hz = dm.getDisplay(Display.DEFAULT_DISPLAY).refreshRate
            val tm = FloatArray(16)
            val sz = IntArray(2)
            var lastTs = 0L
            var ema = 0.0
            var frames = 0
            var gen = true
            var shown = false
            var vis = true

            val zf = FloatArray(32)
            val hf = FloatArray(32)
            var zn = 0
            var hn = 0

            fun tex(unit: Int, t: Int) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            }
            fun loc(p: Int, n: String) = GLES30.glGetUniformLocation(p, n)
            val invAtt = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
            fun target(fbo: Int, w: Int, h: Int) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glViewport(0, 0, w, h)
                if (fbo != 0) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, invAtt, 0)
            }
            fun screen() {
                EGL14.eglQuerySurface(dpy, es, EGL14.EGL_WIDTH, sz, 0)
                EGL14.eglQuerySurface(dpy, es, EGL14.EGL_HEIGHT, sz, 1)
                target(0, sz[0], sz[1])
            }
            fun swap() { if (!EGL14.eglSwapBuffers(dpy, es)) alive = false }
            fun draw() = GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            fun blit(t: Tgt) {
                screen()
                GLES30.glUseProgram(pBlit)
                tex(0, t.tex)
                GLES30.glUniform1i(loc(pBlit, "uTex"), 0)
                GLES30.glUniform4fv(loc(pBlit, "uH"), 8, hf, 0)
                GLES30.glUniform1i(loc(pBlit, "uHn"), hn)
                draw()
            }

            while (alive) {
                if (q.poll(200, TimeUnit.MILLISECONDS) == null) { FgState.baseFps = 0f; continue }
                if (!alive) break
                var n = 1
                while (q.poll() != null) n++
                repeat(n) { stl.updateTexImage() }
                stl.getTransformMatrix(tm)
                val ts = stl.timestamp
                val tIn = System.nanoTime()
                var dt = if (lastTs == 0L) 0.0 else (ts - lastTs) / 1e9 / n
                lastTs = ts

                // ukuran game / resolusi proses berubah -> alokasi ulang tanpa membuat VirtualDisplay baru
                val want = wantSize()
                if (want[0] != cw || want[1] != ch || FgState.scale != scaleUsed) {
                    scaleUsed = FgState.scale
                    k = scaleUsed / 0.5f
                    cw = want[0]; ch = want[1]
                    onOut(cw, ch)
                    prev.release(); cur.release(); prevL.release(); curL.release()
                    mv.release(); mvB.release(); dense.release()
                    useLow = scaleUsed > 0.5f
                    lw = if (useLow) snap(FgState.contentW * 0.5f) else cw
                    lh = if (useLow) snap(FgState.contentH * 0.5f) else ch
                    kM = if (useLow) 1f else k
                    prev = Tgt(cw, ch, GLES30.GL_LINEAR)
                    cur = Tgt(cw, ch, GLES30.GL_LINEAR)
                    prevL = Tgt(lw, lh, GLES30.GL_LINEAR)
                    curL = Tgt(lw, lh, GLES30.GL_LINEAR)
                    mv = Tgt(mvDim(lw, kM), mvDim(lh, kM), GLES30.GL_NEAREST)
                    mvB = Tgt(mvDimF(lw, kM), mvDimF(lh, kM), GLES30.GL_LINEAR)
                    dense = Tgt(lw / 2, lh / 2, GLES30.GL_NEAREST)
                    stl.setDefaultBufferSize(cw, ch)
                    vdl.resize(cw, ch, dpi)
                    frames = 0; lastTs = 0L; ema = 0.0; dt = 0.0
                }

                if (dt > 0.0 && dt < 0.25) FgState.pushFt((dt * 1000.0).toFloat())

                // frame baru -> cur, frame lama -> prev
                val tmp = prev; prev = cur; cur = tmp
                target(cur.fbo, cw, ch)
                GLES30.glUseProgram(pCopy)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ot[0])
                GLES30.glUniform1i(loc(pCopy, "uOes"), 0)
                GLES30.glUniformMatrix4fv(loc(pCopy, "uTex"), 1, false, tm, 0)
                GLES30.glUniform1f(loc(pCopy, "uSharp"), FgState.sharp)
                GLES30.glUniform2f(loc(pCopy, "uTexel"), 1f / cw, 1f / ch)
                qBegin(0)
                draw()
                if (useLow) {
                    val t2 = prevL; prevL = curL; curL = t2
                    target(curL.fbo, lw, lh)
                    GLES30.glUseProgram(pBlit)
                    tex(0, cur.tex)
                    GLES30.glUniform1i(loc(pBlit, "uTex"), 0)
                    GLES30.glUniform1i(loc(pBlit, "uHn"), 0)
                    draw()
                }
                qEnd(0)
                val mCur = if (useLow) curL else cur
                val mPrev = if (useLow) prevL else prev

                frames++
                if (frames % 120 == 0) hz = dm.getDisplay(Display.DEFAULT_DISPLAY).refreshRate
                if (hz < 1f) hz = 60f
                if (dt > 0) ema = if (ema == 0.0 || dt > 0.1) dt else ema * 0.9 + dt * 0.1
                // auto: game sudah >= separuh refresh rate -> jeda; mode paksa: selalu 2x
                val m = if (FgState.sync) 2 else FgState.mult.coerceIn(2, 4)
                // fps game: dari SurfaceFlinger (root) bila segar, jika tidak dari jeda frame tangkapan
                val capFps = if (ema > 0) (1.0 / ema).toFloat() else 0f
                val sfOk = FgState.sfFps > 0f && SystemClock.elapsedRealtime() - FgState.sfAt < 2500
                val base = if (sfOk) FgState.sfFps else capFps
                if (FgState.force) gen = true
                else if (gen && base > 0f && base > hz / (m - 0.4f)) gen = false
                else if (!gen && base > 0f && base < hz / (m - 0.08f)) gen = true
                val on = gen && FgState.enabled
                val stale = frames < 2 || dt <= 0 || dt > 0.08
                FgState.baseFps = base
                FgState.hz = hz
                FgState.outFps = if (on && !stale) (if (FgState.sync) hz else base * m) else base

                if (opaque && on && !vis) { setVisible(true); vis = true }
                val land = FgState.contentW > FgState.contentH
                val zsrc = if (land) FgState.zonesL else FgState.zonesP
                val hsrc = if (land) FgState.holesL else FgState.holesP
                zn = minOf(8, zsrc.size / 4); hn = minOf(8, hsrc.size / 4)
                java.util.Arrays.fill(zf, 0f); java.util.Arrays.fill(hf, 0f)
                System.arraycopy(zsrc, 0, zf, 0, zn * 4)
                System.arraycopy(hsrc, 0, hf, 0, hn * 4)

                if (!on) {
                    if (shown) {
                        if (opaque) {
                            setVisible(false); vis = false // overlay opaque: sembunyikan lewat alpha jendela
                        } else {
                            screen()
                            GLES30.glClearColor(0f, 0f, 0f, 0f)
                            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                            swap()
                        }
                        shown = false
                    }
                } else if (stale) {
                    blit(cur); swap(); shown = true
                } else {
                    qBegin(1)
                    // 1a) estimasi gerak kasar (resolusi gerak)
                    target(mv.fbo, mv.w, mv.h)
                    GLES30.glUseProgram(pMe)
                    tex(0, mCur.tex); tex(1, mPrev.tex)
                    GLES30.glUniform1i(loc(pMe, "uCur"), 0)
                    GLES30.glUniform1i(loc(pMe, "uPrev"), 1)
                    GLES30.glUniform2i(loc(pMe, "uSize"), lw, lh)
                    GLES30.glUniform1f(loc(pMe, "uK"), kM)
                    draw()
                    // 1b) penyempurnaan blok 8*K px, sub-piksel
                    target(mvB.fbo, mvB.w, mvB.h)
                    GLES30.glUseProgram(pMeB)
                    tex(0, mCur.tex); tex(1, mPrev.tex); tex(2, mv.tex)
                    GLES30.glUniform1i(loc(pMeB, "uCur"), 0)
                    GLES30.glUniform1i(loc(pMeB, "uPrev"), 1)
                    GLES30.glUniform1i(loc(pMeB, "uMvA"), 2)
                    GLES30.glUniform2f(loc(pMeB, "uSize"), lw.toFloat(), lh.toFloat())
                    GLES30.glUniform1f(loc(pMeB, "uK"), kM)
                    draw()
                    // 1b2) pass padat per piksel (setengah resolusi gerak)
                    target(dense.fbo, dense.w, dense.h)
                    GLES30.glUseProgram(pDense)
                    tex(0, mCur.tex); tex(1, mPrev.tex); tex(2, mvB.tex)
                    GLES30.glUniform1i(loc(pDense, "uCur"), 0)
                    GLES30.glUniform1i(loc(pDense, "uPrev"), 1)
                    GLES30.glUniform1i(loc(pDense, "uMvB"), 2)
                    GLES30.glUniform2f(loc(pDense, "uSize"), lw.toFloat(), lh.toFloat())
                    GLES30.glUniform2f(loc(pDense, "uDS"), dense.w.toFloat(), dense.h.toFloat())
                    GLES30.glUniform1f(loc(pDense, "uK"), kM)
                    draw()
                    // 1c) selisih global prev/cur (deteksi pergantian adegan, rata-rata lewat mipmap)
                    target(diff.fbo, 64, 64)
                    GLES30.glUseProgram(pDiff)
                    tex(0, mCur.tex); tex(1, mPrev.tex)
                    GLES30.glUniform1i(loc(pDiff, "uCur"), 0)
                    GLES30.glUniform1i(loc(pDiff, "uPrev"), 1)
                    draw()
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    tex(0, diff.tex)
                    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                    qEnd(1)
                    // 2) frame perantara, lalu frame asli.
                    // Mode Sync: jumlah slot = jeda frame sebenarnya x refresh rate (konten bergerak pada kecepatan nyata).
                    // Kalau frame baru sudah tiba, sisa frame perantara dilewati agar tidak menumpuk (latency).
                    // Pacing berbasis waktu (seperti Suzuna): frame perantara ke-i tampil pada tIn + i/slots x jeda frame.
                    // Mode Sync memakai jeda pasangan frame ini; mode lain memakai fps game (SurfaceFlinger bila ada).
                    val ivSec = if (FgState.sync) dt else if (base > 1f) 1.0 / base else ema
                    val ivNs = (ivSec * 1e9).toLong().coerceIn(8_000_000L, 120_000_000L)
                    // jumlah frame yang muat di layar per jeda frame game (agar tidak ada frame yang terbuang)
                    val fit = Math.floor(ivSec * hz + 0.15).toInt().coerceAtLeast(1)
                    val slots = if (FgState.sync) Math.round(dt * hz).toInt().coerceIn(2, 8) else minOf(m, fit)
                    for (fi in 1 until slots) {
                        val early = waitUntil(tIn + ivNs * (fi - 1) / slots)
                        if (early && fi > 1) break
                        screen()
                        GLES30.glUseProgram(pInt)
                        tex(0, cur.tex); tex(1, prev.tex); tex(2, dense.tex); tex(3, diff.tex)
                        GLES30.glUniform1i(loc(pInt, "uCur"), 0)
                        GLES30.glUniform1i(loc(pInt, "uPrev"), 1)
                        GLES30.glUniform1i(loc(pInt, "uMv"), 2)
                        GLES30.glUniform1i(loc(pInt, "uDiff"), 3)
                        GLES30.glUniform2f(loc(pInt, "uSize"), lw.toFloat(), lh.toFloat())
                        GLES30.glUniform1f(loc(pInt, "uT"), fi.toFloat() / slots)
                        GLES30.glUniform1f(loc(pInt, "uK"), kM)
                        GLES30.glUniform4fv(loc(pInt, "uZ"), 8, zf, 0)
                        GLES30.glUniform1i(loc(pInt, "uZn"), zn)
                        GLES30.glUniform4fv(loc(pInt, "uH"), 8, hf, 0)
                        GLES30.glUniform1i(loc(pInt, "uHn"), hn)
                        if (fi == 1) qBegin(2)
                        draw()
                        if (fi == 1) qEnd(2)
                        swap()
                    }
                    waitUntil(tIn + ivNs * (slots - 1) / slots)
                    qBegin(3)
                    blit(cur)
                    qEnd(3)
                    swap(); shown = true
                }
            }
        } catch (e: Throwable) {
            if (alive) onFail(e.message ?: e.javaClass.simpleName)
        } finally {
            alive = false
            FgState.baseFps = 0f
            runCatching { vd?.release() }
            runCatching { cs?.release() }
            runCatching { st?.release() }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (es != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, es)
                if (ec != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ec)
                EGL14.eglTerminate(dpy)
            }
        }
    }
}
