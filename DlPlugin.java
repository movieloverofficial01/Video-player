package com.moflixbd.app;

import android.app.Activity;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "Dl")
public class DlPlugin extends Plugin {

  static class Task {
    volatile boolean stop = false;
    volatile String why = "pause";
  }

  private final Map<String, Task> tasks = new ConcurrentHashMap<>();

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
    Double d = call.getDouble("loaded");
    final long startAt = d == null ? 0 : d.longValue();
    Task old = tasks.get(id);
    if (old != null) old.stop = true;
    final Task t = new Task();
    tasks.put(id, t);
    new Thread(new Runnable() {
      public void run() {
        work(id, url, name, savedUri, startAt, t);
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

  private void work(String id, String url, String name, String savedUri, long startAt, Task t) {
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
    for (int i = 0; i < 6; i++) {
      HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(20000);
      c.setReadTimeout(30000);
      c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
      if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
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
