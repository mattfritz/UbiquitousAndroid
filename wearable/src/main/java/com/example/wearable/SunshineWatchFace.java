/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.wearable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.ImageView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    public final String LOG_TAG = this.getClass().getSimpleName();
    private final String WEATHER_KEY = "com.example.sunshine.app.weather";
    private final String HITEMP_KEY = "com.example.sunshine.app.hitemp";
    private final String LOTEMP_KEY = "com.example.sunshine.app.lotemp";
    private int mWeatherId;
    private int mWeatherImageResourceId;
    private String mHighTemp;
    private String mLowTemp;

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks,
                GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;
        ImageView mWeatherImage;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        float mXOffset;
        float mYOffset;
        float mTempYOffset;
        float mHiTempXOffset;
        float mLoTempXOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private GoogleApiClient mGoogleApiClient;

        private void createGoogleApiClient() {
            if (mGoogleApiClient == null) {
                mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                        .addConnectionCallbacks(this)
                        .addOnConnectionFailedListener(this)
                        .addApi(Wearable.API)
                        .build();
            }
        }

        private void connectGoogleApi() {
            if (!mGoogleApiClient.isConnected()) {
                mGoogleApiClient.connect();
            } else {
                Log.i(LOG_TAG, "Google API Client already connected");
            }
        }

        private void disconnectGoogleApi() {
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                mGoogleApiClient.disconnect();
            } else {
                Log.i(LOG_TAG, "Google API Client already disconnected");
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "Connected to Google services");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            getUpdates();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "Connection to Google services suspended with reason: " + Integer.toString(i));
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(LOG_TAG, "Connection to Google Services failed: " + connectionResult.toString());
        }

        private void getUpdates() {
            Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                    .setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                        @Override
                        public void onResult(@NonNull NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                            String deviceAuthority = "";
                            for (Node node : getConnectedNodesResult.getNodes()) {
                                Log.d(LOG_TAG, "NODE: " + node.getId());
                                deviceAuthority = node.getId();
                            }
                            fetchWeatherUpdates(deviceAuthority);
                        }
                    });
        }

        private void fetchWeatherUpdates(String deviceAuthority) {
            Uri uri = new Uri.Builder()
                    .scheme(PutDataRequest.WEAR_URI_SCHEME)
                    .path("/sunshine-wear")
                    .authority(deviceAuthority)
                    .build();

            Log.d(LOG_TAG, "URI: " + uri.toString());
            Wearable.DataApi.getDataItem(mGoogleApiClient, uri)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            Log.d(LOG_TAG, "DATA REQ SUCCESS: " + dataItemResult.getStatus().isSuccess());
                            Log.d(LOG_TAG, "DATA REQ RESULT: " + dataItemResult.getDataItem());
                            if (dataItemResult.getStatus().isSuccess() && dataItemResult.getDataItem() != null) {
                                DataMap data = DataMap.fromByteArray(dataItemResult.getDataItem().getData());
                                mWeatherId = data.getInt(WEATHER_KEY);
                                mHighTemp = data.getString(HITEMP_KEY);
                                mLowTemp = data.getString(LOTEMP_KEY);
                                mWeatherImageResourceId = getIconResourceForWeatherCondition(mWeatherId);
                                Log.d(LOG_TAG, "WID: " + mWeatherId + " HITEMP: " + mHighTemp + " LOWTEMP: " + mLowTemp);
                            }
                        }
                    });
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "DATA CHANGED");
            getUpdates();
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            createGoogleApiClient();

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.white));

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            disconnectGoogleApi();
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
            // TODO: use this to update weather when visible
            super.onVisibilityChanged(visible);

            if (visible) {
                connectGoogleApi();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                disconnectGoogleApi();
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mTempYOffset = resources.getDimension(R.dimen.temp_y_offset);
            float tempXOffset = resources.getDimension(R.dimen.temp_x_offset);
            mLoTempXOffset = tempXOffset - 50;
            mHiTempXOffset = tempXOffset + 50;

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

                // Weather icon
                Bitmap icon = BitmapFactory.decodeResource(getResources(), mWeatherImageResourceId);
                if (icon != null) {
                    Rect src = new Rect(0, 0, icon.getWidth(), icon.getHeight());
                    Rect dest = new Rect(0, 0, bounds.width(), bounds.height());
                    canvas.drawBitmap(icon, src, dest, null);
                }
            }

            // Current Time
            mTime.setToNow();
            String text = String.format(Locale.getDefault(), "%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);

            String loTemp = getLowTemp();
            canvas.drawText(loTemp, mLoTempXOffset, mTempYOffset, mTextPaint);

            String hiTemp = getHighTemp();
            canvas.drawText(hiTemp, mHiTempXOffset, mTempYOffset, mTextPaint);
        }

        private String getLowTemp() {
            if (mLowTemp != null) {
                return mLowTemp;
            } else {
                return "";
            }
        }

        private String getHighTemp() {
            if (mHighTemp != null) {
                return mHighTemp;
            } else {
                return "";
            }
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

        public int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }
    }
}
