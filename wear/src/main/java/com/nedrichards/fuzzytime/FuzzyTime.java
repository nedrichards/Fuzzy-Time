/*
 * Copyright (C) 2014 The Android Open Source Project
 * Copyright (C) 2015 Nick Richards <nick@nedrichards.com>
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

package com.nedrichards.fuzzytime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class FuzzyTime extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. Update once a minute since that's when things change.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<FuzzyTime.Engine> mWeakReference;

        public EngineHandler(FuzzyTime.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            FuzzyTime.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(FuzzyTime.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = FuzzyTime.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            FuzzyTime.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            FuzzyTime.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = FuzzyTime.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mTime.setToNow();
            String hourText = null;
            switch (mTime.hour) {
                case 0:
                    hourText = "midnight";
                    break;
                case 1:
                    hourText = "one";
                    break;
                case 2:
                    hourText = "two";
                    break;
                case 3:
                    hourText = "three";
                    break;
                case 4:
                    hourText = "four";
                    break;
                case 5:
                    hourText = "five";
                    break;
                case 6:
                    hourText = "six";
                    break;
                case 7:
                    hourText = "seven";
                    break;
                case 8:
                    hourText = "eight";
                    break;
                case 9:
                    hourText = "nine";
                    break;
                case 10:
                    hourText = "ten";
                    break;
                case 11:
                    hourText = "eleven";
                    break;
                case 12:
                    hourText = "noon";
                    break;
                case 13:
                    hourText = "one";
                    break;
                case 14:
                    hourText = "two";
                    break;
                case 15:
                    hourText = "three";
                    break;
                case 16:
                    hourText = "four";
                    break;
                case 17:
                    hourText = "five";
                    break;
                case 18:
                    hourText = "six";
                    break;
                case 19:
                    hourText = "seven";
                    break;
                case 20:
                    hourText = "eight";
                    break;
                case 21:
                    hourText = "nine";
                    break;
                case 22:
                    hourText = "ten";
                    break;
                case 23:
                    hourText = "eleven";
                    break;
            }

            /**
             * Required time patterns:
             * $hourname o'clock
             * five past $hourname
             * ten past $hourname
             * quarter past $hourname
             * twenty past $hourname
             * twenty five past $hourname
             * half past $hourname
             * twenty five to $hourname
             * twenty to $hourname
             * quarter to $hourname
             * ten to $hourname
             * five to $hourname
             *
             * Noon
             * Midnight
             */

            String minuteText = null;
            switch (mTime.minute) {
                case 58:
                case 59:
                case 0:
                case 1:
                case 2:
                    minuteText = " o'clock";
                    break;
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    minuteText = "five past ";
                    break;
                case 8:
                case 9:
                case 10:
                case 11:
                case 12:
                    minuteText = "ten past ";
                    break;
                case 13:
                case 14:
                case 15:
                case 16:
                case 17:
                    minuteText = "quarter past ";
                    break;
                case 18:
                case 19:
                case 20:
                case 21:
                case 22:
                    minuteText = "twenty past ";
                    break;
                case 23:
                case 24:
                case 25:
                case 26:
                case 27:
                    minuteText = "twentyfive past ";
                    break;
                case 28:
                case 29:
                case 30:
                case 31:
                case 32:
                    minuteText = "half past ";
                    break;
                case 33:
                case 34:
                case 35:
                case 36:
                case 37:
                    minuteText = "twentyfive to ";
                    break;
                case 38:
                case 39:
                case 40:
                case 41:
                case 42:
                    minuteText = "twenty to ";
                    break;
                case 43:
                case 44:
                case 45:
                case 46:
                case 47:
                    minuteText = "quarter to ";
                    break;
                case 48:
                case 49:
                case 50:
                case 51:
                case 52:
                case 53:
                    minuteText = "ten to ";
                    break;
                case 54:
                case 55:
                case 56:
                case 57:
                    minuteText = "five to ";
                    break;
            }

            String timeText = null;
            switch (mTime.minute) {
                case 58:
                case 59:
                case 0:
                case 1:
                case 2:
                    // make sure to treat midday and midnight correctly
                    if (mTime.hour == 0) {
                        timeText = hourText;
                    }
                    if (mTime.hour == 12) {
                        timeText = hourText;
                    } else {
                        timeText = hourText + minuteText;
                    }
                    break;
                default:
                    timeText = minuteText + hourText;
                    break;
            }

            /* I'm not treating ambient mode differently, everything is ambient
                    String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d", mTime.hour, mTime.minute);

            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);
                    */
            canvas.drawText(timeText, mXOffset, mYOffset, mTextPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }


        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
