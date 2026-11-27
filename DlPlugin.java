package com.moflixbd.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "Dl")
public class DlPlugin extends Plugin {

  public static volatile boolean immersive = false;

  static class Task {
    volatile boolean stop = false;
    volatile String why = "pause";
  }

  private final Map<String, Task> tasks = new ConcurrentHashMap<>();

  private long dirSize(File f) {
    File[] l = f.listFiles();
    if (l == null) return f.length();
    long s = 0;
    for (File c : l) s += c.isDirectory() ? dirSize(c) : c.length();
    return s;
  }

  private void delDir(File f, boolean keep) {
    File[] l = f.listFiles();
    if (l != null) for (File c : l) delDir(c, false);
    if (!keep) f.delete();
  }

  @PluginMethod
  public void cacheSize(final PluginCall call) {
    new Thread(new Runnable() {
      public void run() {
        JSObject o = new JSObject();
        o.put("size", dirSize(getContext().getCacheDir()));
        call.resolve(o);
      }
    }).start();
  }

  @PluginMethod
  public void cacheClear(final PluginCall call) {
    new Thread(new Runnable() {
      public void run() {
        delDir(getContext().getCacheDir(), true);
        call.resolve();
      }
    }).start();
  }

  @PluginMethod
  public void clip(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      public void run() {
        String t = "";
        try {
          ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
          ClipData d = cm.getPrimaryClip();
          if (d != null && d.getItemCount() > 0) {
            CharSequence c = d.getItemAt(0).coerceToText(getContext());
            if (c != null) t = c.toString();
          }
        } catch (Exception e) { }
        JSObject o = new JSObject();
        o.put("text", t);
        call.resolve(o);
      }
    });
  }

  @PluginMethod
  public void copy(final PluginCall call) {
    final String text = call.getString("text", "");
    getActivity().runOnUiThread(new Runnable() {
      public void run() {
        try {
          ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
          cm.setPrimaryClip(ClipData.newPlainText("link", text));
          call.resolve();
        } catch (Exception e) {
          call.reject(String.valueOf(e.getMessage()));
        }
      }
    });
  }

  @PluginMethod
  public void getVol(PluginCall call) {
    AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    int cu = am.getStreamVolume(AudioManager.STREAM_MUSIC);
    JSObject o = new JSObject();
    o.put("v", mx > 0 ? (double) cu / mx : 0);
    o.put("max", mx);
    call.resolve(o);
  }

  @PluginMethod
  public void vol(PluginCall call) {
    Double d = call.getDouble("v", 0.0);
    AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
    int mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    double x = Math.max(0.0, Math.min(1.0, d));
    am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) Math.round(x * mx), 0);
    call.resolve();
  }

  @PluginMethod
  public void getBright(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      public void run() {
        float b = 0.5f;
        try {
          b = getActivity().getWindow().getAttributes().screenBrightness;
          if (b < 0) {
            int s = Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            b = s / 255f;
          }
        } catch (Exception e) { }
        JSObject o = new JSObject();
        o.put("v", b);
        call.resolve(o);
      }
    });
  }

  @PluginMethod
  public void ram(PluginCall call) {
    ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
    am.getMemoryInfo(mi);
    JSObject o = new JSObject();
    o.put("gb", mi.totalMem / 1073741824.0);
    call.resolve(o);
  }

  @PluginMethod
  public void storage(PluginCall call) {
    try {
      StatFs sf = new StatFs(Environment.getExternalStorageDirectory().getPath());
      JSObject o = new JSObject();
      o.put("free", sf.getAvailableBytes());
      o.put("total", sf.getTotalBytes());
      call.resolve(o);
    } catch (Exception e) {
      call.reject(String.valueOf(e.getMessage()));
    }
  }

  @PluginMethod
  public void screen(PluginCall call) {
    final String orient = call.getString("orient", "auto");
    final boolean imm = Boolean.TRUE.equals(call.getBoolean("immersive", false));
    getActivity().runOnUiThread(new Runnable() {
      public void run() {
        Activity a = getActivity();
        immersive = imm;
        int o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if ("landscape".equals(orient)) o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        else if ("portrait".equals(orient)) o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        a.setRequestedOrientation(o);
        Window w = a.getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
          WindowManager.LayoutParams lp = w.getAttributes();
          lp.layoutInDisplayCutoutMode = imm
            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
          w.setAttributes(lp);
        }
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        if (imm) {
          c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
          c.hide(WindowInsetsCompat.Type.systemBars());
        } else {
          c.show(WindowInsetsCompat.Type.systemBars());
        }
        c.setAppearanceLightStatusBars(false);
        c.setAppearanceLightNavigationBars(false);
        ViewCompat.requestApplyInsets(a.findViewById(android.R.id.content));
      }
    });
    call.resolve();
  }

  @PluginMethod
  public void info(final PluginCall call) {
    final String url = call.getString("url");
    new Thread(new Runnable() {
      public void run() {
        try {
          HttpURLConnection c = open(url, 0);
          long len = c.getContentLengthLong();
          boolean res = "bytes".equalsIgnoreCase(c.getHeaderField("Accept-Ranges"));
          c.disconnect();
          JSObject o = new JSObject();
          o.put("size", len > 0 ? len : 0);
          o.put("res", res);
          call.resolve(o);
        } catch (Exception e) {
          call.reject(String.valueOf(e.getMessage()));
        }
      }
    }).start();
  }

  @PluginMethod
  public void start(final PluginCall call) {
    final String id = call.getString("id");
    final String url = call.getString("url");
    String nm = call.getString("name", "video.mp4");
    if (nm == null || nm.trim().isEmpty()) nm = "video.mp4";
    final String name = nm.replaceAll("[\\\\/:*?\"<>|]", "_");
    final String savedUri = call.getString("uri", "");
    final int conn = call.getInt("conn", 1);
    Double d = call.getDouble("loaded");
    final long startAt = d == null ? 0 : d.longValue();
    Task old = tasks.get(id);
    if (old != null) old.stop = true;
    final Task t = new Task();
    tasks.put(id, t);
    new Thread(new Runnable() {
      public void run() {
        work(id, url, name, savedUri, startAt, t, conn);
      }
    }).start();
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call) {
    Task t = tasks.get(call.getString("id"));
    if (t != null) {
      t.why = call.getString("why", "pause");
      t.stop = true;
    }
    call.resolve();
  }

  @PluginMethod
  public void remove(PluginCall call) {
    String uri = call.getString("uri", "");
    if (uri != null && !uri.isEmpty()) delete(getContext().getContentResolver(), uri);
    call.resolve();
  }

  private void work(String id, String url, String name, String savedUri, long startAt, Task t, int conn) {
    ContentResolver cr = getContext().getContentResolver();
    HttpURLConnection con = null;
    InputStream in = null;
    OutputStream out = null;
    String uri = savedUri;
    long loaded = 0;
    long total = -1;
    boolean res = false;
    try {
      if (uri != null && !uri.isEmpty() && startAt > 0) {
        long[][] sg = loadSegs(uri);
        if (sg != null) {
          long tot = sg[sg.length - 1][0];
          runSegments(id, url, uri, tot, java.util.Arrays.copyOf(sg, sg.length - 1), t);
          return;
        }
      }
      if (uri != null && !uri.isEmpty() && startAt > 0) {
        loaded = sizeOf(cr, uri);
        if (loaded < 0) loaded = 0;
      }
      if (uri == null || uri.isEmpty() || loaded == 0) {
        if (uri != null && !uri.isEmpty()) delete(cr, uri);
        uri = create(cr, name);
        loaded = 0;
      }
      con = open(url, loaded);
      int code = con.getResponseCode();
      if (code != 200 && code != 206) throw new IOException("HTTP " + code);
      boolean append = loaded > 0 && code == 206;
      if (loaded > 0 && code != 206) loaded = 0;
      long len = con.getContentLengthLong();
      String range = con.getHeaderField("Content-Range");
      if (code == 206 && range != null && range.indexOf('/') > 0) {
        try {
          total = Long.parseLong(range.substring(range.indexOf('/') + 1).trim());
        } catch (Exception e) {
          total = -1;
        }
      } else if (len > 0) {
        total = loaded + len;
      }
      res = code == 206 || "bytes".equalsIgnoreCase(con.getHeaderField("Accept-Ranges"));
      if (conn > 1 && res && total > 0 && loaded == 0) {
        con.disconnect();
        con = null;
        runSegments(id, url, uri, total, makeSegs(total, conn), t);
        return;
      }
      out = openOut(cr, uri, append);
      in = con.getInputStream();
      emit(id, "run", loaded, total, 0, uri, res, null);
      byte[] buf = new byte[65536];
      int n;
      long last = System.currentTimeMillis();
      long lastB = loaded;
      while (!t.stop && (n = in.read(buf)) != -1) {
        out.write(buf, 0, n);
        loaded += n;
        long now = System.currentTimeMillis();
        if (now - last >= 500) {
          long speed = (loaded - lastB) * 1000 / (now - last);
          last = now;
          lastB = loaded;
          emit(id, "run", loaded, total, speed, uri, res, null);
        }
      }
      out.flush();
      out.close();
      out = null;
      if (t.stop) {
        if ("cancel".equals(t.why)) {
          delete(cr, uri);
          emit(id, "cancel", 0, total, 0, "", res, null);
        } else {
          emit(id, "pause", loaded, total, 0, uri, res, null);
        }
        return;
      }
      finish(cr, uri);
      emit(id, "done", loaded, loaded, 0, uri, res, null);
    } catch (Exception e) {
      emit(id, "err", loaded, total, 0, uri == null ? "" : uri, res, String.valueOf(e.getMessage()));
    } finally {
      try { if (out != null) out.close(); } catch (Exception e) { }
      try { if (in != null) in.close(); } catch (Exception e) { }
      if (con != null) con.disconnect();
      if (tasks.get(id) == t) tasks.remove(id);
    }
  }

  private HttpURLConnection open(String u, long from) throws IOException {
    return open(u, from, -1);
  }

  private long[][] makeSegs(long total, int n) {
    long size = (total + n - 1) / n;
    long[][] r = new long[n][3];
    for (int i = 0; i < n; i++) {
      long a = i * size;
      long b = Math.min(total - 1, a + size - 1);
      r[i][0] = a;
      r[i][1] = a;
      r[i][2] = b;
    }
    return r;
  }

  private long sumDone(long[][] segs) {
    long d = 0;
    for (long[] s : segs) d += Math.max(0, Math.min(s[1], s[2] + 1) - s[0]);
    return d;
  }

  private void saveSegs(String uri, long total, long[][] segs) {
    StringBuilder b = new StringBuilder();
    b.append(total);
    for (long[] s : segs) b.append(';').append(s[0]).append(':').append(s[1]).append(':').append(s[2]);
    getContext().getSharedPreferences("mfdl", 0).edit().putString(uri, b.toString()).apply();
  }

  private void clearSegs(String uri) {
    getContext().getSharedPreferences("mfdl", 0).edit().remove(uri).apply();
  }

  private long[][] loadSegs(String uri) {
    String v = getContext().getSharedPreferences("mfdl", 0).getString(uri, null);
    if (v == null) return null;
    try {
      String[] p = v.split(";");
      long[][] r = new long[p.length][3];
      for (int i = 1; i < p.length; i++) {
        String[] q = p[i].split(":");
        r[i - 1][0] = Long.parseLong(q[0]);
        r[i - 1][1] = Long.parseLong(q[1]);
        r[i - 1][2] = Long.parseLong(q[2]);
      }
      r[p.length - 1][0] = Long.parseLong(p[0]);
      return r;
    } catch (Exception e) {
      return null;
    }
  }

  private void segment(String url, long[] s, FileChannel ch, Task t, String[] err) {
    HttpURLConnection c = null;
    InputStream in = null;
    try {
      if (s[1] > s[2]) return;
      c = open(url, s[1], s[2]);
      int code = c.getResponseCode();
      if (code != 206) throw new IOException("Server ignored range (HTTP " + code + ")");
      in = c.getInputStream();
      byte[] buf = new byte[65536];
      int n;
      while (!t.stop && err[0] == null && s[1] <= s[2] && (n = in.read(buf)) != -1) {
        int w = (int) Math.min((long) n, s[2] - s[1] + 1);
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, w);
        long p = s[1];
        while (bb.hasRemaining()) p += ch.write(bb, p);
        s[1] += w;
      }
      if (s[1] <= s[2] && !t.stop && err[0] == null) throw new IOException("Connection closed early");
    } catch (Exception e) {
      err[0] = String.valueOf(e.getMessage());
    } finally {
      try { if (in != null) in.close(); } catch (Exception e) { }
      if (c != null) c.disconnect();
    }
  }

  private void runSegments(String id, String url, String uri, long total, long[][] segs, Task t) {
    ContentResolver cr = getContext().getContentResolver();
    ParcelFileDescriptor pfd = null;
    RandomAccessFile raf = null;
    final String[] err = new String[1];
    try {
      FileChannel ch;
      if (uri.startsWith("file:")) {
        raf = new RandomAccessFile(new File(Uri.parse(uri).getPath()), "rw");
        ch = raf.getChannel();
      } else {
        pfd = cr.openFileDescriptor(Uri.parse(uri), "rw");
        if (pfd == null) throw new IOException("Cannot open file");
        ch = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
      }
      final FileChannel fch = ch;
      final String fu = url;
      final Task ft = t;
      Thread[] th = new Thread[segs.length];
      for (int i = 0; i < segs.length; i++) {
        final long[] s = segs[i];
        th[i] = new Thread(new Runnable() {
          public void run() {
            segment(fu, s, fch, ft, err);
          }
        });
        th[i].start();
      }
      long last = System.currentTimeMillis();
      long lastB = sumDone(segs);
      emit(id, "run", lastB, total, 0, uri, true, null);
      boolean alive = true;
      while (alive) {
        try { Thread.sleep(500); } catch (InterruptedException e) { }
        alive = false;
        for (Thread x : th) if (x.isAlive()) alive = true;
        long now = System.currentTimeMillis();
        long done = sumDone(segs);
        long speed = (done - lastB) * 1000 / Math.max(1, now - last);
        last = now;
        lastB = done;
        if (alive) emit(id, "run", done, total, speed, uri, true, null);
      }
      for (Thread x : th) {
        try { x.join(); } catch (InterruptedException e) { }
      }
      long done = sumDone(segs);
      if (err[0] != null) {
        saveSegs(uri, total, segs);
        emit(id, "err", done, total, 0, uri, true, err[0]);
      } else if (t.stop) {
        if ("cancel".equals(t.why)) {
          delete(cr, uri);
          clearSegs(uri);
          emit(id, "cancel", 0, total, 0, "", true, null);
        } else {
          saveSegs(uri, total, segs);
          emit(id, "pause", done, total, 0, uri, true, null);
        }
      } else {
        finish(cr, uri);
        clearSegs(uri);
        emit(id, "done", total, total, 0, uri, true, null);
      }
    } catch (Exception e) {
      emit(id, "err", 0, total, 0, uri, true, String.valueOf(e.getMessage()));
    } finally {
      try { if (pfd != null) pfd.close(); } catch (Exception e) { }
      try { if (raf != null) raf.close(); } catch (Exception e) { }
      if (tasks.get(id) == t) tasks.remove(id);
    }
  }

  private HttpURLConnection open(String u, long from, long to) throws IOException {
    for (int i = 0; i < 6; i++) {
      HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(20000);
      c.setReadTimeout(30000);
      c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
      if (from > 0 || to >= 0) c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
      int code = c.getResponseCode();
      if (code >= 300 && code < 400) {
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc == null) throw new IOException("Bad redirect");
        u = new URL(new URL(u), loc).toString();
        continue;
      }
      return c;
    }
    throw new IOException("Too many redirects");
  }

  private String create(ContentResolver cr, String name) throws IOException {
    if (Build.VERSION.SDK_INT >= 29) {
      ContentValues v = new ContentValues();
      v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      v.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name));
      v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MoflixBD");
      v.put(MediaStore.MediaColumns.IS_PENDING, 1);
      Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
      if (u == null) throw new IOException("Cannot create file");
      return u.toString();
    }
    File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if (dir == null) throw new IOException("No storage");
    dir.mkdirs();
    return Uri.fromFile(new File(dir, name)).toString();
  }

  private OutputStream openOut(ContentResolver cr, String uri, boolean append) throws IOException {
    if (uri.startsWith("file:")) return new FileOutputStream(new File(Uri.parse(uri).getPath()), append);
    OutputStream o = cr.openOutputStream(Uri.parse(uri), append ? "wa" : "wt");
    if (o == null) throw new IOException("Cannot open file");
    return o;
  }

  private long sizeOf(ContentResolver cr, String uri) {
    try {
      if (uri.startsWith("file:")) return new File(Uri.parse(uri).getPath()).length();
      ParcelFileDescriptor p = cr.openFileDescriptor(Uri.parse(uri), "r");
      if (p == null) return -1;
      long s = p.getStatSize();
      p.close();
      return s;
    } catch (Exception e) {
      return -1;
    }
  }

  private void finish(ContentResolver cr, String uri) {
    if (uri.startsWith("file:")) return;
    ContentValues v = new ContentValues();
    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
    cr.update(Uri.parse(uri), v, null, null);
  }

  private void delete(ContentResolver cr, String uri) {
    try {
      if (uri.startsWith("file:")) new File(Uri.parse(uri).getPath()).delete();
      else cr.delete(Uri.parse(uri), null, null);
    } catch (Exception e) { }
  }

  private String mimeOf(String n) {
    String s = n.toLowerCase(Locale.ROOT);
    if (s.endsWith(".mkv")) return "video/x-matroska";
    if (s.endsWith(".webm")) return "video/webm";
    if (s.endsWith(".mp4") || s.endsWith(".m4v")) return "video/mp4";
    if (s.endsWith(".avi")) return "video/x-msvideo";
    if (s.endsWith(".mov")) return "video/quicktime";
    if (s.endsWith(".mp3")) return "audio/mpeg";
    return "application/octet-stream";
  }

  private void emit(String id, String state, long loaded, long total, long speed, String uri, boolean res, String err) {
    JSObject o = new JSObject();
    o.put("id", id);
    o.put("state", state);
    o.put("loaded", loaded);
    o.put("total", total);
    o.put("speed", speed);
    o.put("uri", uri == null ? "" : uri);
    o.put("res", res);
    if (err != null) o.put("err", err);
    notifyListeners("progress", o);
  }
}
