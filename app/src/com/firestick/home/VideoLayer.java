package com.firestick.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Plays aerial videos one after another on a SurfaceView (so 4K reaches the screen at full
 * resolution), silent.
 *
 * Nothing ever cuts to black or to a photo: a "still" image sits right above the video and shows
 * a snapshot of the last frame (taken with PixelCopy) whenever the next video is loading, then
 * dissolves into it once it moves. Leaving the screen remembers the video, the position and that
 * frame (kept on disk too, for after a restart), so coming back shows the same picture at once
 * and carries on from the same spot.
 */
final class VideoLayer implements SurfaceHolder.Callback {
    private static final String TAG = "MyHome";
    private static final long DISSOLVE_MS = 1100;
    /** After this many failures in a row the owner falls back to photos. */
    private static final int GIVE_UP = 3;
    /** How often a playing video's frame is snapshotted, so there is always a recent one. */
    private static final long SNAPSHOT_MS = 4_000;

    interface Listener {
        /** The first frame of a video is on screen. */
        void onPlaying();

        /** Videos will not play (offline, or the servers refuse); show something else. */
        void onFailed();
    }

    static final String UHD = "video4k", HD = "videohd", PHOTOS = "photos";

    /** The owner's choice of background: 4K video, HD video or photos. */
    static String mode(Context c) {
        return prefs(c).getString("background", UHD);
    }

    static void setMode(Context c, String mode) {
        prefs(c).edit().putString("background", mode).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("look", Context.MODE_PRIVATE);
    }

    /** Shared by the home screen and the screensaver, and kept while the app runs. */
    private static Bitmap lastFrame;
    private static Aerials.Video lastVideo;
    private static int lastPosition;

    private final Context context;
    private final SurfaceView surface;
    private final ImageView still;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Aerials.Video current;
    private boolean wanted, haveSurface, uhd, rendering;
    private int failures;
    private long lastSnapshot;

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            MediaPlayer p = player;
            if (p == null || !rendering) return;
            try {
                int left = p.getDuration() - p.getCurrentPosition();
                if (p.getDuration() > 0 && left < 600) {
                    next();
                    return;
                }
                lastPosition = p.getCurrentPosition();
            } catch (IllegalStateException ignored) {
            }
            if (System.currentTimeMillis() - lastSnapshot > SNAPSHOT_MS) snapshot(null);
            ui.postDelayed(this, 400);
        }
    };

    VideoLayer(Context context, SurfaceView surface, ImageView still, Listener listener) {
        this.context = context.getApplicationContext();
        this.surface = surface;
        this.still = still;
        this.listener = listener;
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        still.setBackgroundColor(0xFF000000);
        surface.getHolder().addCallback(this);
    }

    private File frameFile() {
        return new File(context.getFilesDir(), "last-frame.jpg");
    }

    /** The picture to show the moment the screen appears: the last frame seen, if any. */
    private Bitmap frame() {
        if (lastFrame == null && frameFile().isFile()) lastFrame = BitmapFactory.decodeFile(frameFile().getPath());
        return lastFrame;
    }

    void start(boolean uhd) {
        this.uhd = uhd;
        wanted = true;
        failures = 0;
        still.animate().cancel();
        Bitmap f = frame();
        if (f != null) still.setImageBitmap(f);
        still.setAlpha(1f);
        if (haveSurface && player == null) play(lastVideo, lastPosition);
    }

    /** Stops playing; remembers where it was and keeps the current frame on screen. */
    void stop() {
        if (rendering && player != null) {
            try {
                lastVideo = current;
                lastPosition = player.getCurrentPosition();
            } catch (IllegalStateException ignored) {
            }
            snapshot(null);
        }
        wanted = false;
        release();
        still.animate().cancel();
        if (lastFrame != null) still.setImageBitmap(lastFrame);
        still.setAlpha(1f);
        saveFrame();
    }

    /** Forgets the remembered video, so the next start picks a new one. */
    void skip() {
        lastVideo = null;
        lastPosition = 0;
        if (wanted) next();
    }

    private void release() {
        ui.removeCallbacks(watch);
        rendering = false;
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
    }

    /** Copies the frame on screen into {@link #lastFrame}; then runs {@code done} (true if it worked). */
    private void snapshot(final Callback done) {
        lastSnapshot = System.currentTimeMillis();
        if (!haveSurface || surface.getWidth() == 0) {
            if (done != null) done.run(false);
            return;
        }
        int w = Math.min(surface.getWidth(), 1920), h = Math.min(surface.getHeight(), 1080);
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surface, b, new PixelCopy.OnPixelCopyFinishedListener() {
                @Override
                public void onPixelCopyFinished(int result) {
                    boolean ok = result == PixelCopy.SUCCESS;
                    if (ok) lastFrame = b;
                    if (done != null) done.run(ok);
                }
            }, ui);
        } catch (IllegalArgumentException e) {
            if (done != null) done.run(false);
        }
    }

    private interface Callback {
        void run(boolean ok);
    }

    private void saveFrame() {
        final Bitmap b = lastFrame;
        if (b == null) return;
        final File f = frameFile();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(f.getPath() + ".part");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    b.compress(Bitmap.CompressFormat.JPEG, 90, out);
                } catch (Exception e) {
                    return;
                }
                tmp.renameTo(f);
            }
        }).start();
    }

    /** Freezes the current frame on the still, then loads the next video underneath it. */
    private void next() {
        ui.removeCallbacks(watch);
        lastVideo = null;
        lastPosition = 0;
        snapshot(new Callback() {
            @Override
            public void run(boolean ok) {
                still.animate().cancel();
                if (ok) still.setImageBitmap(lastFrame);
                still.setAlpha(1f);
                if (wanted && haveSurface) play(null, 0);
            }
        });
    }

    private void play(Aerials.Video resume, final int position) {
        release();
        final Aerials.Video v = resume != null ? resume : Aerials.next(context);
        if (v == null) {
            listener.onFailed();
            return;
        }
        current = v;
        final MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setDataSource(uhd ? v.uhd : v.hd);
            p.setDisplay(surface.getHolder());
            p.setVolume(0f, 0f);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (mp != player) return;
                    mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    int d = mp.getDuration();
                    if (position > 0 && (d <= 0 || position < d - 5000)) mp.seekTo(position);
                    mp.start();
                }
            });
            p.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (mp == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        failures = 0;
                        rendering = true;
                        // Give the decoder a moment past the first frame, then dissolve.
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (rendering) still.animate().alpha(0f).setDuration(DISSOLVE_MS).start();
                            }
                        }, 250);
                        listener.onPlaying();
                        ui.removeCallbacks(watch);
                        ui.postDelayed(watch, 1000);
                    }
                    return false;
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (mp == player) next();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.w(TAG, "video failed " + what + "/" + extra);
                    if (mp != player) return true;
                    failed();
                    return true;
                }
            });
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "video failed to start", e);
            failed();
        }
    }

    private void failed() {
        release();
        lastVideo = null;
        lastPosition = 0;
        if (!wanted) return;
        if (++failures >= GIVE_UP) {
            listener.onFailed();
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wanted && haveSurface) play(null, 0);
            }
        }, 1500);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        haveSurface = true;
        if (wanted && player == null) play(lastVideo, lastPosition);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        haveSurface = false;
        release();
    }
}
