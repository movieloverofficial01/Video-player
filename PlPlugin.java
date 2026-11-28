package com.moflixbd.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.audiofx.BassBoost;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.activity.result.ActivityResult;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.PlayerView;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@CapacitorPlugin(name = "Pl")
public class PlPlugin extends Plugin {

  private ExoPlayer player;
  private PlayerView pv;
  private LoudnessEnhancer le;
  private BassBoost bb;
  private boolean ready = false;
  private String curUri = "";
  private final List<MediaItem.SubtitleConfiguration> subs = new ArrayList<>();
  private static SimpleCache vcache;
  private static long vmax = 0;
  private static StandaloneDatabaseProvider dbp;

  private synchronized SimpleCache cache(long maxBytes) {
    if (vcache != null && vmax == maxBytes) return vcache;
    if (vcache != null) {
      try { vcache.release(); } catch (Exception e) { }
      vcache = null;
    }
    File dir = new File(getContext().getCacheDir(), "vcache");
    if (dbp == null) dbp = new StandaloneDatabaseProvider(getContext());
    vcache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(maxBytes), dbp);
    vmax = maxBytes;
    return vcache;
  }

  private long dirSize(File f) {
    File[] l = f.listFiles();
    if (l == null) return f.length();
    long s = 0;
    for (File c : l) s += c.isDirectory() ? dirSize(c) : c.length();
    return s;
  }

  private void delDir(File f) {
    File[] l = f.listFiles();
    if (l != null) for (File c : l) delDir(c);
    f.delete();
  }
  private final Handler handler = new Handler(Looper.getMainLooper());

  private final Runnable tick = new Runnable() {
    public void run() {
      if (player == null) return;
      notifyListeners("pl", snap("time"));
      handler.postDelayed(this, 400);
    }
  };

  private final Player.Listener listener = new Player.Listener() {
    @Override
    public void onPlaybackStateChanged(int s) {
      if (s == Player.STATE_READY && !ready) {
        ready = true;
        notifyListeners("pl", snap("ready"));
      } else if (s == Player.STATE_ENDED) {
        notifyListeners("pl", snap("ended"));
      } else {
        notifyListeners("pl", snap("state"));
      }
    }

    @Override
    public void onIsPlayingChanged(boolean p) {
      notifyListeners("pl", snap("time"));
    }

    @Override
    public void onVideoSizeChanged(VideoSize vs) {
      notifyListeners("pl", snap("size"));
    }

    @Override
    public void onTracksChanged(Tracks t) {
      notifyListeners("pl", snap("tracks"));
    }

    @Override
    public void onRenderedFirstFrame() {
      notifyListeners("pl", snap("frame"));
    }

    @Override
    public void onPlayerError(PlaybackException e) {
      JSObject o = new JSObject();
      o.put("t", "error");
      o.put("msg", String.valueOf(e.getMessage()) + " (" + e.getErrorCodeName() + ")");
      notifyListeners("pl", o);
    }
  };

  private void ui(Runnable r) {
    getActivity().runOnUiThread(r);
  }

  private JSObject snap(String type) {
    JSObject o = new JSObject();
    o.put("t", type);
    if (player != null) {
      long d = player.getDuration();
      o.put("pos", player.getCurrentPosition());
      o.put("dur", d == C.TIME_UNSET ? 0 : d);
      o.put("buf", player.getBufferedPosition());
      o.put("pw", player.getPlayWhenReady());
      o.put("wait", player.getPlaybackState() == Player.STATE_BUFFERING);
      VideoSize vs = player.getVideoSize();
      o.put("w", vs.width);
      o.put("h", vs.height);
      if (!"time".equals(type)) putTracks(o);
    }
    return o;
  }

  private void putTracks(JSObject o) {
    JSArray a = new JSArray();
    JSArray t = new JSArray();
    int ai = 0;
    int ti = 0;
    for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
      int type = g.getType();
      if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
      for (int i = 0; i < g.length; i++) {
        Format f = g.getTrackFormat(i);
        boolean audio = type == C.TRACK_TYPE_AUDIO;
        JSObject x = new JSObject();
        String lb = label(f, audio);
        if (!g.isTrackSupported(i)) lb = lb + " (no decoder)";
        x.put("label", lb);
        x.put("lang", f.language == null ? "" : f.language);
        x.put("sel", g.isTrackSelected(i));
        if (audio) {
          x.put("i", ai++);
          a.put(x);
        } else {
          x.put("i", ti++);
          t.put(x);
        }
      }
    }
    o.put("audio", a);
    o.put("text", t);
  }

  private String label(Format f, boolean audio) {
    String s = f.label;
    if (s == null || s.isEmpty()) {
      if (f.language != null && !"und".equals(f.language)) {
        s = new Locale(f.language).getDisplayLanguage();
      }
    }
    if (s == null || s.isEmpty()) s = audio ? "Audio" : "Subtitle";
    if (audio) {
      String m = f.sampleMimeType == null ? "" : f.sampleMimeType.toLowerCase(Locale.ROOT);
      String c = "";
      if (m.contains("eac3")) c = "EAC3";
      else if (m.contains("ac3")) c = "AC3";
      else if (m.contains("dts") || m.contains("vnd.dts")) c = "DTS";
      else if (m.contains("true-hd")) c = "TrueHD";
      else if (m.contains("mp4a") || m.contains("aac")) c = "AAC";
      else if (m.contains("opus")) c = "Opus";
      else if (m.contains("vorbis")) c = "Vorbis";
      else if (m.contains("flac")) c = "FLAC";
      else if (m.contains("mpeg")) c = "MP3";
      if (!c.isEmpty()) s = s + " · " + c;
      if (f.channelCount > 0) s = s + " · " + f.channelCount + "ch";
    }
    return s;
  }

  private void ensureView() {
    if (pv != null) return;
    WebView wv = getBridge().getWebView();
    ViewGroup p = (ViewGroup) wv.getParent();
    pv = new PlayerView(getContext());
    pv.setUseController(false);
    pv.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
    pv.setBackgroundColor(Color.BLACK);
    pv.setKeepScreenOn(true);
    p.addView(pv, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    pv.setVisibility(View.GONE);
  }

  private void release() {
    handler.removeCallbacks(tick);
    if (le != null) {
      try { le.release(); } catch (Exception e) { }
      le = null;
    }
    if (bb != null) {
      try { bb.release(); } catch (Exception e) { }
      bb = null;
    }
    if (player != null) {
      player.removeListener(listener);
      player.release();
      player = null;
    }
    ready = false;
  }

  private void applyMax(int h) {
    if (player == null) return;
    if (h > 0) {
      player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(Integer.MAX_VALUE, h).build());
    } else {
      player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearVideoSizeConstraints().build());
    }
  }

  @PluginMethod
  public void open(final PluginCall call) {
    final String url = call.getString("url");
    final double speed = call.getDouble("speed", 1.0);
    final int bmin = call.getInt("bmin", 15000);
    final int bmax = call.getInt("bmax", 50000);
    final int maxh = call.getInt("maxh", 0);
    final String dec = call.getString("dec", "HW+");
    final int vmb = call.getInt("vmb", 512);
    ui(new Runnable() {
      public void run() {
        try {
          ensureView();
          release();
          DefaultHttpDataSource.Factory hf = new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
          DefaultDataSource.Factory df = new DefaultDataSource.Factory(getContext(), hf);
          int mode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
          if ("HW".equals(dec)) mode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;
          else if ("SW".equals(dec)) mode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
          MediaCodecSelector sel = new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(String mime, boolean secure, boolean tunneling) throws MediaCodecUtil.DecoderQueryException {
              List<MediaCodecInfo> all = new ArrayList<MediaCodecInfo>(MediaCodecUtil.getDecoderInfos(mime, secure, tunneling));
              if ("HW".equals(dec)) {
                List<MediaCodecInfo> hw = new ArrayList<MediaCodecInfo>();
                for (MediaCodecInfo i : all) if (i.hardwareAccelerated && !i.softwareOnly) hw.add(i);
                return hw.isEmpty() ? all : hw;
              }
              if ("SW".equals(dec)) {
                List<MediaCodecInfo> out = new ArrayList<MediaCodecInfo>();
                for (MediaCodecInfo i : all) if (i.softwareOnly) out.add(i);
                for (MediaCodecInfo i : all) if (!i.softwareOnly) out.add(i);
                return out;
              }
              return all;
            }
          };
          DefaultRenderersFactory rf = new DefaultRenderersFactory(getContext())
            .setExtensionRendererMode(mode)
            .setEnableDecoderFallback(!"HW".equals(dec))
            .setMediaCodecSelector(sel);
          int mn = Math.max(1000, bmin);
          int mx = Math.max(mn, bmax);
          DefaultLoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(mn, mx, Math.min(2500, mn), Math.min(5000, mn))
            .build();
          DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(df);
          if (url.startsWith("http") && vmb > 0) {
            try {
              CacheDataSource.Factory cf = new CacheDataSource.Factory()
                .setCache(cache(vmb * 1048576L))
                .setUpstreamDataSourceFactory(df)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
              msf = new DefaultMediaSourceFactory(cf);
            } catch (Exception e) { }
          }
          curUri = url;
          subs.clear();
          player = new ExoPlayer.Builder(getContext(), rf)
            .setMediaSourceFactory(msf)
            .setLoadControl(lc)
            .build();
          player.addListener(listener);
          pv.setPlayer(player);
          pv.setScaleX(1f);
          pv.setScaleY(1f);
          applyMax(maxh);
          player.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
          player.setPlaybackSpeed((float) speed);
          player.prepare();
          player.setPlayWhenReady(true);
          pv.setVisibility(View.VISIBLE);
          getBridge().getWebView().setBackgroundColor(Color.TRANSPARENT);
          handler.post(tick);
          call.resolve();
        } catch (Exception e) {
          call.reject(String.valueOf(e.getMessage()));
        }
      }
    });
  }

  @PluginMethod
  public void close(final PluginCall call) {
    ui(new Runnable() {
      public void run() {
        release();
        if (pv != null) {
          pv.setPlayer(null);
          pv.setVisibility(View.GONE);
        }
        getBridge().getWebView().setBackgroundColor(0xFF15141A);
        Window w = getActivity().getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        w.setAttributes(lp);
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void play(PluginCall call) {
    ui(() -> {
      if (player != null) {
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0);
        player.play();
      }
    });
    call.resolve();
  }

  @PluginMethod
  public void pause(PluginCall call) {
    ui(() -> {
      if (player != null) player.pause();
    });
    call.resolve();
  }

  @PluginMethod
  public void seek(PluginCall call) {
    final double ms = call.getDouble("ms", 0.0);
    ui(() -> {
      if (player != null) player.seekTo((long) ms);
    });
    call.resolve();
  }

  @PluginMethod
  public void speed(PluginCall call) {
    final double v = call.getDouble("v", 1.0);
    ui(() -> {
      if (player != null) player.setPlaybackSpeed((float) v);
    });
    call.resolve();
  }

  @PluginMethod
  public void volume(PluginCall call) {
    final double v = call.getDouble("v", 1.0);
    ui(() -> {
      if (player != null) player.setVolume((float) v);
    });
    call.resolve();
  }

  @PluginMethod
  public void loop(PluginCall call) {
    final boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
    ui(() -> {
      if (player != null) player.setRepeatMode(on ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    });
    call.resolve();
  }

  @PluginMethod
  public void fit(PluginCall call) {
    final String m = call.getString("m", "contain");
    ui(() -> {
      if (pv == null) return;
      if ("cover".equals(m)) pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
      else if ("fill".equals(m)) pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
      else pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
    });
    call.resolve();
  }

  @PluginMethod
  public void zoom(PluginCall call) {
    final double z = call.getDouble("z", 1.0);
    ui(() -> {
      if (pv == null) return;
      pv.setScaleX((float) z);
      pv.setScaleY((float) z);
    });
    call.resolve();
  }

  @PluginMethod
  public void bright(PluginCall call) {
    final double v = call.getDouble("v", 1.0);
    ui(() -> {
      Window w = getActivity().getWindow();
      WindowManager.LayoutParams lp = w.getAttributes();
      if (v < 0) {
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
      } else {
        lp.screenBrightness = (float) Math.max(0.02, Math.min(1.0, (v - 0.3) / 1.2));
      }
      w.setAttributes(lp);
    });
    call.resolve();
  }

  @PluginMethod
  public void addSub(final PluginCall call) {
    final String name = call.getString("name", "Subtitle");
    final String text = call.getString("text", "");
    ui(() -> {
      try {
        if (player == null) {
          call.reject("no player");
          return;
        }
        File f = new File(getContext().getCacheDir(), "sub" + System.nanoTime() + ".vtt");
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(text.getBytes("UTF-8"));
        fo.close();
        List<MediaItem.SubtitleConfiguration> list = new ArrayList<>();
        for (MediaItem.SubtitleConfiguration c : subs) list.add(c.buildUpon().setSelectionFlags(0).build());
        list.add(new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
          .setMimeType(MimeTypes.TEXT_VTT)
          .setLanguage("zz" + list.size())
          .setLabel(name)
          .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
          .build());
        subs.clear();
        subs.addAll(list);
        long pos = player.getCurrentPosition();
        boolean pw = player.getPlayWhenReady();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        player.setMediaItem(new MediaItem.Builder().setUri(Uri.parse(curUri)).setSubtitleConfigurations(list).build(), pos);
        player.prepare();
        player.setPlayWhenReady(pw);
        call.resolve();
      } catch (Exception e) {
        call.reject(String.valueOf(e.getMessage()));
      }
    });
  }

  @PluginMethod
  public void subStyle(PluginCall call) {
    final String sz = call.getString("sz", "Normal");
    final String col = call.getString("col", "White");
    final String bgs = call.getString("bgs", "Shadow");
    ui(() -> {
      if (pv == null) return;
      SubtitleView sv = pv.getSubtitleView();
      if (sv == null) return;
      float f = 0.0533f;
      if ("Small".equals(sz)) f = 0.04f;
      else if ("Large".equals(sz)) f = 0.07f;
      else if ("Extra large".equals(sz)) f = 0.09f;
      int fg = 0xFFFFFFFF;
      if ("Yellow".equals(col)) fg = 0xFFFFE066;
      else if ("Cyan".equals(col)) fg = 0xFF66FFFF;
      int bg = "Box".equals(bgs) ? 0xCC000000 : 0;
      int edge = CaptionStyleCompat.EDGE_TYPE_NONE;
      if ("Outline".equals(bgs)) edge = CaptionStyleCompat.EDGE_TYPE_OUTLINE;
      else if ("Shadow".equals(bgs)) edge = CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW;
      sv.setApplyEmbeddedStyles(false);
      sv.setApplyEmbeddedFontSizes(false);
      sv.setFractionalTextSize(f);
      sv.setStyle(new CaptionStyleCompat(fg, bg, Color.TRANSPARENT, edge, Color.BLACK, null));
    });
    call.resolve();
  }

  @PluginMethod
  public void vcInfo(final PluginCall call) {
    final int mb = call.getInt("mb", 512);
    new Thread(new Runnable() {
      public void run() {
        long v = 0;
        try {
          SimpleCache c = player != null ? vcache : cache(mb * 1048576L);
          if (c != null) v = c.getCacheSpace();
        } catch (Exception e) { }
        long all = dirSize(getContext().getCacheDir());
        long vd = dirSize(new File(getContext().getCacheDir(), "vcache"));
        JSObject o = new JSObject();
        o.put("video", v);
        o.put("other", Math.max(0, all - vd));
        call.resolve(o);
      }
    }).start();
  }

  @PluginMethod
  public void vcClear(final PluginCall call) {
    final int mb = call.getInt("mb", 512);
    new Thread(new Runnable() {
      public void run() {
        try {
          SimpleCache c = player != null ? vcache : cache(mb * 1048576L);
          if (c != null) {
            for (String k : new ArrayList<String>(c.getKeys())) c.removeResource(k);
          }
        } catch (Exception e) { }
        File[] l = getContext().getCacheDir().listFiles();
        if (l != null) {
          for (File x : l) {
            if (!"vcache".equals(x.getName())) delDir(x);
          }
        }
        call.resolve();
      }
    }).start();
  }

  @PluginMethod
  public void vcClean(final PluginCall call) {
    final int mb = call.getInt("mb", 512);
    final int days = call.getInt("days", 7);
    new Thread(new Runnable() {
      public void run() {
        try {
          SimpleCache c = player != null ? vcache : cache(mb * 1048576L);
          if (c != null) {
            long cutoff = System.currentTimeMillis() - days * 86400000L;
            for (String k : new ArrayList<String>(c.getKeys())) {
              boolean old = true;
              for (CacheSpan s : c.getCachedSpans(k)) {
                if (s.lastTouchTimestamp >= cutoff) {
                  old = false;
                  break;
                }
              }
              if (old) c.removeResource(k);
            }
          }
        } catch (Exception e) { }
        call.resolve();
      }
    }).start();
  }

  @PluginMethod
  public void cap(PluginCall call) {
    final int h = call.getInt("h", 0);
    ui(() -> applyMax(h));
    call.resolve();
  }

  @PluginMethod
  public void audio(PluginCall call) {
    final int idx = call.getInt("i", 0);
    ui(() -> select(C.TRACK_TYPE_AUDIO, idx));
    call.resolve();
  }

  @PluginMethod
  public void sub(PluginCall call) {
    final int idx = call.getInt("i", -1);
    ui(() -> {
      if (player == null) return;
      if (idx < 0) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
      } else {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
        select(C.TRACK_TYPE_TEXT, idx);
      }
    });
    call.resolve();
  }

  private void select(int type, int idx) {
    if (player == null) return;
    int n = 0;
    for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
      if (g.getType() != type) continue;
      for (int i = 0; i < g.length; i++) {
        if (n == idx) {
          player.setTrackSelectionParameters(
            player.getTrackSelectionParameters().buildUpon()
              .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i))
              .build());
          return;
        }
        n++;
      }
    }
  }

  @PluginMethod
  public void fx(PluginCall call) {
    final int boost = call.getInt("boost", 100);
    final int bass = call.getInt("bass", 0);
    ui(() -> {
      if (player == null) return;
      try {
        int sid = player.getAudioSessionId();
        if (le == null) le = new LoudnessEnhancer(sid);
        le.setTargetGain(Math.max(0, boost - 100) * 6);
        le.setEnabled(boost > 100);
      } catch (Exception e) { }
      try {
        if (bass > 0) {
          if (bb == null) bb = new BassBoost(0, player.getAudioSessionId());
          bb.setStrength((short) (bass * 10));
          bb.setEnabled(true);
        } else if (bb != null) {
          bb.setEnabled(false);
        }
      } catch (Exception e) { }
    });
    call.resolve();
  }

  @PluginMethod
  public void pip(PluginCall call) {
    try {
      if (Build.VERSION.SDK_INT >= 26) {
        getActivity().enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
        call.resolve();
      } else {
        call.reject("PiP needs Android 8 or newer");
      }
    } catch (Exception e) {
      call.reject(String.valueOf(e.getMessage()));
    }
  }

  @PluginMethod
  public void pick(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"video/*", "audio/*", "application/x-matroska"});
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(call, i, "pickDone");
  }

  @ActivityCallback
  private void pickDone(PluginCall call, ActivityResult r) {
    if (call == null) return;
    JSArray arr = new JSArray();
    Intent d = r.getData();
    if (r.getResultCode() == Activity.RESULT_OK && d != null) {
      if (d.getClipData() != null) {
        for (int k = 0; k < d.getClipData().getItemCount(); k++) arr.put(fileInfo(d.getClipData().getItemAt(k).getUri()));
      } else if (d.getData() != null) {
        arr.put(fileInfo(d.getData()));
      }
    }
    JSObject o = new JSObject();
    o.put("files", arr);
    call.resolve(o);
  }

  private JSObject fileInfo(Uri u) {
    try {
      getContext().getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception e) { }
    String name = "video";
    long size = 0;
    Cursor c = null;
    try {
      c = getContext().getContentResolver().query(u, null, null, null, null);
      if (c != null && c.moveToFirst()) {
        int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        int si = c.getColumnIndex(OpenableColumns.SIZE);
        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
        if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
      }
    } catch (Exception e) {
    } finally {
      if (c != null) c.close();
    }
    JSObject o = new JSObject();
    o.put("uri", u.toString());
    o.put("name", name);
    o.put("size", size);
    return o;
  }

  @Override
  protected void handleOnDestroy() {
    release();
  }
}
