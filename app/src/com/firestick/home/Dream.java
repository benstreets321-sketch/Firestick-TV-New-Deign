package com.firestick.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.service.dreams.DreamService;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Date;
import java.util.List;

/**
 * The screensaver: the same aerial videos full screen, with a quiet clock and date in the
 * corner. Without internet it shows one of the saved photos instead.
 */
public final class Dream extends DreamService {
    private VideoLayer video;
    private ImageView photo;
    private ImageView veil;
    private TextView clock, date;
    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, match());
        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setVisibility(View.GONE);
        root.addView(photo, match());
        veil = new ImageView(this);
        root.addView(veil, match());

        LinearLayout corner = new LinearLayout(this);
        corner.setOrientation(LinearLayout.VERTICAL);
        corner.setGravity(Gravity.END);
        clock = text(54, "sans-serif-light");
        date = text(16, "sans-serif");
        corner.addView(clock);
        corner.addView(date);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.gravity = Gravity.BOTTOM | Gravity.END;
        cl.rightMargin = dp(56);
        cl.bottomMargin = dp(44);
        root.addView(corner, cl);
        setContentView(root);

        video = new VideoLayer(this, surface, veil, new VideoLayer.Listener() {
            @Override
            public void onPlaying() {}

            @Override
            public void onFailed() {
                showPhoto();
            }
        });
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private TextView text(float sp, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xE6FFFFFF);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(dp(8), 0, dp(1), 0xAA000000);
        t.setGravity(Gravity.END);
        return t;
    }

    private void tick() {
        Date now = new Date();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE d MMMM", now));
    }

    private void showPhoto() {
        List<File> photos = Wallpapers.all(this);
        if (photos.isEmpty()) return;
        photo.setImageBitmap(BitmapFactory.decodeFile(photos.get(0).getPath()));
        photo.setVisibility(View.VISIBLE);
        veil.animate().alpha(0f).setDuration(1200).start();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(time, f);
        tick();
        if (VideoLayer.PHOTOS.equals(VideoLayer.mode(this))) showPhoto();
        else video.start(VideoLayer.UHD.equals(VideoLayer.mode(this)));
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        video.stop();
        try {
            unregisterReceiver(time);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
