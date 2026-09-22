package com.evsuite.abrp;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Drives {@link OtaUpdater} — UNSTABLE BUILDS ONLY.
 *
 * Upstream keeps this seam inert pending a safety and legal audit of the published suite.
 * This fork publishes its own builds from its own repository, signed with the platform key
 * the head unit already trusts, so that audit does not govern here and the seam is live.
 *
 * Installation goes through {@code pm install -r}, which only a platform-signed build may
 * call. {@link OtaUpdater#install} checks the downloaded APK's certificate against the
 * running app's first and refuses a mismatch, so an update signed with any other key is
 * deleted rather than installed — the same check that makes an upstream APK unusable here.
 */
final class UpdateHook {

    private static final String TAG = "EVABRP.Update";

    private UpdateHook() { }

    static boolean isSupported() { return true; }

    /** Result of one check, for the UI to report. */
    interface Listener {
        void onResult(String message);
    }

    /** Fire-and-forget check on app start. Nothing is reported; failures stay in the log. */
    static void checkInBackground(Context context) {
        checkInBackground(context, null);
    }

    /**
     * Checks, downloads and installs in one background pass.
     *
     * The whole sequence runs off the main thread because every step blocks: the release
     * API call, the download, and {@code pm install}. [listener] is called back on the main
     * thread, so a UI caller can write straight to a view.
     */
    static void checkInBackground(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try {
                message = runCheck(app);
            } catch (Throwable t) {
                Log.w(TAG, "Update check threw", t);
                message = "Güncelleme kontrolü başarısız";
            }
            if (listener != null) {
                final String result = message;
                main.post(() -> listener.onResult(result));
            }
        }, "ota-check").start();
    }

    /** The blocking part. Returns a line describing what happened, for the UI. */
    private static String runCheck(Context app) {
        String current;
        try {
            current = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "Sürüm okunamadı";
        }

        OtaUpdater.Update update = OtaUpdater.check(current);
        if (update == null) return "Güncel (" + current + ")";

        Log.i(TAG, "Update available: " + update.versionName);
        File apk = OtaUpdater.download(app, update);
        if (apk == null) return "İndirme başarısız (" + update.versionName + ")";

        // The cached APK is cleared whichever way install goes: a rejected archive must not
        // linger, and an accepted one has already been copied out by the package manager.
        boolean installed = OtaUpdater.install(app, apk);
        if (!apk.delete()) Log.w(TAG, "Could not remove cached OTA APK");

        return installed
                ? "Güncellendi: " + update.versionName + " — yeniden başlatın"
                : "Kurulum reddedildi (imza uyuşmuyor?)";
    }
}
