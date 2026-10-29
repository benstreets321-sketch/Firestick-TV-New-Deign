package com.firestick.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

/**
 * Plays aerial videos one after another on a SurfaceView (so 4K reaches the screen at full
 * resolution), silent, fading through black between them. A black "veil" view sits right above
 * the SurfaceView and does the fades.
 */
final class VideoLayer implements SurfaceHolder.Callback {
    private static final String TAG = "MyHome";
    private static final long FADE_MS = 1200;
    /** After this many failures in a row the owner falls back to photos. */
    private static final int GIVE_UP = 3;

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

    private final Context context;
    private final SurfaceView surface;
    private final View veil;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private boolean wanted, haveSurface, uhd;
    private int failures;

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            MediaPlayer p = player;
            if (p == null) return;
            try {
                int left = p.getDuration() - p.getCurrentPosition();
                if (p.getDuration() > 0 && left < FADE_MS + 300) {
                    fadeToNext();
                    return;
                }
            } catch (IllegalStateException ignored) {
            }
            ui.postDelayed(this, 400);
        }
    };

    VideoLayer(Context context, SurfaceView surface, View veil, Listener listener) {
        this.context = context;
        this.surface = surface;
        this.veil = veil;
        this.listener = listener;
        surface.getHolder().addCallback(this);
    }

    void start(boolean uhd) {
        this.uhd = uhd;
        wanted = true;
        failures = 0;
        veil.animate().cancel();
        veil.setAlpha(1f);
        if (haveSurface && player == null) play();
    }

    void stop() {
        wanted = false;
        release();
        veil.animate().cancel();
        veil.setAlpha(1f);
    }

    private void release() {
        ui.removeCallbacks(watch);
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
    }

    private void play() {
        release();
        Aerials.Video v = Aerials.next(context);
        if (v == null) {
            listener.onFailed();
            return;
        }
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
                    mp.start();
                }
            });
            p.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (mp == player && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        failures = 0;
                        veil.animate().alpha(0f).setDuration(FADE_MS).start();
                        listener.onPlaying();
                        ui.postDelayed(watch, 1000);
                    }
                    return false;
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    if (mp == player) fadeToNext();
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
        if (!wanted) return;
        if (++failures >= GIVE_UP) {
            listener.onFailed();
            return;
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wanted && haveSurface) play();
            }
        }, 1500);
    }

    private void fadeToNext() {
        ui.removeCallbacks(watch);
        veil.animate().alpha(1f).setDuration(FADE_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (wanted && haveSurface) play();
            }
        }).start();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        haveSurface = true;
        if (wanted && player == null) play();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        haveSurface = false;
        release();
    }
}
