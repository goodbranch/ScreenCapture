package com.branch.www.screencapture;

/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.media.MediaActionSound;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.ImageView;

/**
 * POD used in the AsyncTask which saves an image in the background.
 */
class SaveImageInBackgroundData {
  Context context;
  Bitmap image;
  Uri imageUri;
  Runnable finisher;
  int iconSize;
  int result;

  void clearImage() {
    image = null;
    imageUri = null;
    iconSize = 0;
  }

  void clearContext() {
    context = null;
  }
}


/**
 * TODO: - Performance when over gl surfaces? Ie. Gallery - what do we say in the Toast? Which icon
 * do we get if the user uses another type of gallery?
 */
class GlobalScreenshot {
  private static final String TAG = "GlobalScreenshot";

  private static final int SCREENSHOT_FLASH_TO_PEAK_DURATION = 130;
  private static final int SCREENSHOT_DROP_IN_DURATION = 430;
  private static final int SCREENSHOT_DROP_OUT_DELAY = 500;
  private static final int SCREENSHOT_DROP_OUT_DURATION = 430;
  private static final int SCREENSHOT_DROP_OUT_SCALE_DURATION = 370;
  private static final int SCREENSHOT_FAST_DROP_OUT_DURATION = 320;
  private static final float BACKGROUND_ALPHA = 0.5f;
  private static final float SCREENSHOT_SCALE = 1f;
  private static final float SCREENSHOT_DROP_IN_MIN_SCALE = SCREENSHOT_SCALE * 0.725f;
  private static final float SCREENSHOT_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.45f;
  private static final float SCREENSHOT_FAST_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.6f;
  private static final float SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET = 0f;

  private Context mContext;
  private WindowManager mWindowManager;
  private WindowManager.LayoutParams mWindowLayoutParams;
  private Display mDisplay;
  private DisplayMetrics mDisplayMetrics;

  private Bitmap mScreenBitmap;
  private View mScreenshotLayout;
  private ImageView mBackgroundView;
  private ImageView mScreenshotView;
  private ImageView mScreenshotFlash;

  private AnimatorSet mScreenshotAnimation;

  private float mBgPadding;
  private float mBgPaddingScale;

  private MediaActionSound mCameraSound;


  private onScreenShotListener mOnScreenShotListener;

  /**
   * @param context everything needs a context :(
   */
  public GlobalScreenshot(Context context) {
    Resources r = context.getResources();
    mContext = context;
    LayoutInflater layoutInflater = (LayoutInflater)
        context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

    // Inflate the screenshot layout
    mScreenshotLayout = layoutInflater.inflate(R.layout.global_screenshot, null);
    mBackgroundView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot_background);
    mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot);
    mScreenshotFlash = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot_flash);
    mScreenshotLayout.setFocusable(true);
    mScreenshotLayout.setOnTouchListener(new View.OnTouchListener() {
      @Override
      public boolean onTouch(View v, MotionEvent event) {
        // Intercept and ignore all touch events
        return true;
      }
    });

    // Setup the window that we are going to use
    mWindowLayoutParams = new WindowManager.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
        WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        PixelFormat.TRANSLUCENT);
    mWindowLayoutParams.setTitle("ScreenshotAnimation");
    mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

    mDisplay = mWindowManager.getDefaultDisplay();
    mDisplayMetrics = new DisplayMetrics();
    mDisplay.getRealMetrics(mDisplayMetrics);

    // Scale has to account for both sides of the bg
    mBgPadding = (float) r.getDimensionPixelSize(R.dimen.global_screenshot_bg_padding);
    mBgPaddingScale = mBgPadding / mDisplayMetrics.widthPixels;

    // Setup the Camera shutter sound
    mCameraSound = new MediaActionSound();
    mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
  }


  /**
   * Takes a screenshot of the current display and shows an animation.
   */
  void takeScreenshot(Bitmap bitmap, onScreenShotListener onScreenShotListener, boolean statusBarVisible, boolean navBarVisible) {
    // Take the screenshot
    mScreenBitmap = bitmap;
    this.mOnScreenShotListener = onScreenShotListener;

    if (mOnScreenShotListener != null) {
      mOnScreenShotListener.onStartShot();
    }

    if (mScreenBitmap == null) {
      notifyScreenshotError(mContext);
      return;
    }

    // Optimizations
    mScreenBitmap.setHasAlpha(false);
    mScreenBitmap.prepareToDraw();

    // Start the post-screenshot animation
    startAnimation(mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels,
        statusBarVisible, navBarVisible);
  }


  /**
   * Starts the animation after taking the screenshot
   */
  private void startAnimation(int w, int h, boolean statusBarVisible,
                              boolean navBarVisible) {
    // Add the view for the animation
    mScreenshotView.setImageBitmap(mScreenBitmap);
    mScreenshotLayout.requestFocus();

    // Setup the animation with the screenshot just taken
    if (mScreenshotAnimation != null) {
      mScreenshotAnimation.end();
      mScreenshotAnimation.removeAllListeners();
    }

    mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
    ValueAnimator screenshotDropInAnim = createScreenshotDropInAnimation();
    ValueAnimator screenshotFadeOutAnim = createScreenshotDropOutAnimation(w, h,
        statusBarVisible, navBarVisible);
    mScreenshotAnimation = new AnimatorSet();
    mScreenshotAnimation.playSequentially(screenshotDropInAnim, screenshotFadeOutAnim);
    mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        // Save the screenshot once we have a bit of time now
        saveScreenshotInWorkerThread();
        mWindowManager.removeView(mScreenshotLayout);

        // Clear any references to the bitmap
        mScreenBitmap = null;
        mScreenshotView.setImageBitmap(null);

      }
    });
    mScreenshotLayout.post(new Runnable() {
      @Override
      public void run() {
        // Play the shutter sound to notify that we've taken a screenshot
        mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

        mScreenshotView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mScreenshotView.buildLayer();
        mScreenshotAnimation.start();
      }
    });
  }

  private ValueAnimator createScreenshotDropInAnimation() {
    final float flashPeakDurationPct = ((float) (SCREENSHOT_FLASH_TO_PEAK_DURATION)
        / SCREENSHOT_DROP_IN_DURATION);
    final float flashDurationPct = 2f * flashPeakDurationPct;
    final Interpolator flashAlphaInterpolator = new Interpolator() {
      @Override
      public float getInterpolation(float x) {
        // Flash the flash view in and out quickly
        if (x <= flashDurationPct) {
          return (float) Math.sin(Math.PI * (x / flashDurationPct));
        }
        return 0;
      }
    };
    final Interpolator scaleInterpolator = new Interpolator() {
      @Override
      public float getInterpolation(float x) {
        // We start scaling when the flash is at it's peak
        if (x < flashPeakDurationPct) {
          return 0;
        }
        return (x - flashDurationPct) / (1f - flashDurationPct);
      }
    };
    ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    anim.setDuration(SCREENSHOT_DROP_IN_DURATION);
    anim.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationStart(Animator animation) {
        mBackgroundView.setAlpha(0f);
        mBackgroundView.setVisibility(View.VISIBLE);
        mScreenshotView.setAlpha(0f);
        mScreenshotView.setTranslationX(0f);
        mScreenshotView.setTranslationY(0f);
        mScreenshotView.setScaleX(SCREENSHOT_SCALE + mBgPaddingScale);
        mScreenshotView.setScaleY(SCREENSHOT_SCALE + mBgPaddingScale);
        mScreenshotView.setVisibility(View.VISIBLE);
        mScreenshotFlash.setAlpha(0f);
        mScreenshotFlash.setVisibility(View.VISIBLE);
      }

      @Override
      public void onAnimationEnd(Animator animation) {
        mScreenshotFlash.setVisibility(View.GONE);

//        mScreenshotView.setScaleX(SCREENSHOT_SCALE);
//        mScreenshotView.setScaleY(SCREENSHOT_SCALE);
      }
    });
    anim.addUpdateListener(new AnimatorUpdateListener() {
      @Override
      public void onAnimationUpdate(ValueAnimator animation) {
        float t = (Float) animation.getAnimatedValue();
        float scaleT = (SCREENSHOT_SCALE + mBgPaddingScale)
            - scaleInterpolator.getInterpolation(t)
            * (SCREENSHOT_SCALE - SCREENSHOT_DROP_IN_MIN_SCALE);
        mBackgroundView.setAlpha(scaleInterpolator.getInterpolation(t) * BACKGROUND_ALPHA);
        mScreenshotView.setAlpha(t);
        mScreenshotView.setScaleX(scaleT);
        mScreenshotView.setScaleY(scaleT);
        mScreenshotFlash.setAlpha(flashAlphaInterpolator.getInterpolation(t));
      }
    });
    return anim;
  }

  private ValueAnimator createScreenshotDropOutAnimation(int w, int h, boolean statusBarVisible,
                                                         boolean navBarVisible) {
    ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    anim.setStartDelay(SCREENSHOT_DROP_OUT_DELAY);
    anim.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        mBackgroundView.setVisibility(View.GONE);
        mScreenshotView.setVisibility(View.GONE);
        mScreenshotView.setLayerType(View.LAYER_TYPE_NONE, null);
      }
    });

    if (!statusBarVisible || !navBarVisible) {
      // There is no status bar/nav bar, so just fade the screenshot away in place
      anim.setDuration(SCREENSHOT_FAST_DROP_OUT_DURATION);
      anim.addUpdateListener(new AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
          float t = (Float) animation.getAnimatedValue();
          float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
              - t * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_FAST_DROP_OUT_MIN_SCALE);
          mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
          mScreenshotView.setAlpha(1f - t);
          mScreenshotView.setScaleX(scaleT);
          mScreenshotView.setScaleY(scaleT);
        }
      });
    } else {
      // In the case where there is a status bar, animate to the origin of the bar (top-left)
      final float scaleDurationPct = (float) SCREENSHOT_DROP_OUT_SCALE_DURATION
          / SCREENSHOT_DROP_OUT_DURATION;
      final Interpolator scaleInterpolator = new Interpolator() {
        @Override
        public float getInterpolation(float x) {
          if (x < scaleDurationPct) {
            // Decelerate, and scale the input accordingly
            return (float) (1f - Math.pow(1f - (x / scaleDurationPct), 2f));
          }
          return 1f;
        }
      };

      // Determine the bounds of how to scale
      float halfScreenWidth = (w - 2f * mBgPadding) / 2f;
      float halfScreenHeight = (h - 2f * mBgPadding) / 2f;
      final float offsetPct = SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET;
      final PointF finalPos = new PointF(
          -halfScreenWidth + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenWidth,
          -halfScreenHeight + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenHeight);

      // Animate the screenshot to the status bar
      anim.setDuration(SCREENSHOT_DROP_OUT_DURATION);
      anim.addUpdateListener(new AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
          float t = (Float) animation.getAnimatedValue();
          float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
              - scaleInterpolator.getInterpolation(t)
              * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_DROP_OUT_MIN_SCALE);
          mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
          mScreenshotView.setAlpha(1f - scaleInterpolator.getInterpolation(t));
          mScreenshotView.setScaleX(scaleT);
          mScreenshotView.setScaleY(scaleT);
          mScreenshotView.setTranslationX(t * finalPos.x);
          mScreenshotView.setTranslationY(t * finalPos.y);
        }
      });
    }
    return anim;
  }

  private void notifyScreenshotError(Context context) {
    if (mOnScreenShotListener != null) {
      mOnScreenShotListener.onFinishShot(false);
    }
  }

  private void saveScreenshotInWorkerThread() {
    if (mOnScreenShotListener != null) {
      mOnScreenShotListener.onFinishShot(true);
    }
  }


  public interface onScreenShotListener {

    public void onStartShot();

    public void onFinishShot(boolean success);
  }
}
