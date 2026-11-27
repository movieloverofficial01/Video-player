package com.moflixbd.app;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
    registerPlugin(DlPlugin.class);
    registerPlugin(PlPlugin.class);
    super.onCreate(savedInstanceState);
    Window w = getWindow();
    int dark = 0xFF15141A;
    w.getDecorView().setBackgroundColor(dark);
    w.setStatusBarColor(dark);
    w.setNavigationBarColor(dark);
    if (Build.VERSION.SDK_INT >= 29) {
      w.setNavigationBarContrastEnforced(false);
    }
    WindowCompat.setDecorFitsSystemWindows(w, false);
    WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
    c.setAppearanceLightStatusBars(false);
    c.setAppearanceLightNavigationBars(false);
    final View content = findViewById(android.R.id.content);
    ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
      if (DlPlugin.immersive) {
        v.setPadding(0, 0, 0, 0);
      } else {
        Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
        v.setPadding(b.left, b.top, b.right, b.bottom);
      }
      return WindowInsetsCompat.CONSUMED;
    });
    ViewCompat.requestApplyInsets(content);
  }

  @Override
  public void onResume() {
    super.onResume();
    Window w = getWindow();
    WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
    c.setAppearanceLightStatusBars(false);
    c.setAppearanceLightNavigationBars(false);
  }
}
