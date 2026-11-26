package com.moflixbd.app;

import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(DlPlugin.class);
    super.onCreate(savedInstanceState);
    Window w = getWindow();
    int dark = 0xFF15141A;
    w.getDecorView().setBackgroundColor(dark);
    w.setStatusBarColor(dark);
    w.setNavigationBarColor(dark);
    if (Build.VERSION.SDK_INT >= 29) {
      w.setNavigationBarContrastEnforced(false);
    }
    WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
    c.setAppearanceLightStatusBars(false);
    c.setAppearanceLightNavigationBars(false);
  }
}
