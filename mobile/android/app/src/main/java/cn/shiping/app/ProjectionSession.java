package cn.shiping.app;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.DisplayManager;
import android.view.Surface;

/** One system consent, one projection, shared by audio and screenshot foreground services. */
final class ProjectionSession {
    private static MediaProjection projection;
    private static int owners;
    private static VirtualDisplay display;
    static synchronized boolean available() { return projection != null; }
    static synchronized MediaProjection acquire(Context context, int result, Intent data) {
        if (projection == null) {
            if (data == null) throw new IllegalStateException("共享授权已结束，请重新授权");
            MediaProjection created = context.getSystemService(MediaProjectionManager.class).getMediaProjection(result, data);
            projection = created;
            created.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { synchronized (ProjectionSession.class) { if (projection == created) { projection = null; owners = 0; if (display != null) { display.release(); display = null; } } } }
            }, new Handler(Looper.getMainLooper()));
        }
        owners++; return projection;
    }
    static synchronized VirtualDisplay attachDisplay(MediaProjection value, int width, int height, int density, Surface surface) {
        if (value != projection) throw new IllegalStateException("共享授权已结束");
        if (display == null) display = value.createVirtualDisplay("ShipingCapture", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, new Handler(Looper.getMainLooper()));
        else { display.resize(width, height, density); display.setSurface(surface); }
        return display;
    }
    static synchronized void detachDisplay(VirtualDisplay value) { if (value != null && value == display) display.setSurface(null); }
    static synchronized void release(MediaProjection value) {
        if (value != projection) return;
        if (--owners <= 0) { projection = null; owners = 0; if (display != null) { display.release(); display = null; } value.stop(); }
    }
}
