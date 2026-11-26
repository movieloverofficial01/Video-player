package com.moflixbd.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(DlPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
