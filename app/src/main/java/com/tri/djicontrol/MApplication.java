package com.tri.djicontrol;

import android.app.Application;
import android.content.Context;

public class MApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Giải mã DJI SDK
        com.secneo.sdk.Helper.install(this);
    }
}
