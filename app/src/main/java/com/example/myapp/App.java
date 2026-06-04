package com.example.myapp;

import android.os.Build;
import android.os.StrictMode;
import androidx.multidex.MultiDexApplication;
import com.example.myapp.extractor.NewPipeDownloader;
import org.schabi.newpipe.extractor.NewPipe;

/**
 * Application class.
 *
 * FIX 1: Extends MultiDexApplication — required on API 28 for large dex counts.
 * FIX 2: StrictMode permissive on API 28 — some OEM ROMs (Pie) have aggressive
 *         thread policies that kill reflective init inside NewPipe.
 * FIX 3: NewPipe.init() called on main thread — safe, it only stores the reference.
 */
public class App extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();

        // FIX: Relax StrictMode on Android 9 to prevent OEM ROM crashes during init
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder().permitAll().build()
            );
        }

        // Init NewPipe — safe on main thread (no network, just stores downloader ref)
        NewPipe.init(NewPipeDownloader.getInstance());
    }
}
