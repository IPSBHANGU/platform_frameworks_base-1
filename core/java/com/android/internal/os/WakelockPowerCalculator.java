/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.os;

import android.os.BatteryConsumer;
import android.os.BatteryStats;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.os.Process;
import android.os.UidBatteryConsumer;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.List;

public class WakelockPowerCalculator extends PowerCalculator {
    private static final String TAG = "WakelockPowerCalculator";
    private static final boolean DEBUG = BatteryStatsHelper.DEBUG;
    private final UsageBasedPowerEstimator mPowerEstimator;

    private static class PowerAndDuration {
        public long durationMs;
        public double powerMah;
    }

    public WakelockPowerCalculator(PowerProfile profile) {
        mPowerEstimator = new UsageBasedPowerEstimator(
                profile.getAveragePower(PowerProfile.POWER_CPU_IDLE));
    }

    @Override
    public void calculate(BatteryUsageStats.Builder builder, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, BatteryUsageStatsQuery query) {
        final PowerAndDuration result = new PowerAndDuration();
        UidBatteryConsumer.Builder osBatteryConsumer = null;
        double osPowerMah = 0;
        long osDurationMs = 0;
        long totalAppDurationMs = 0;
        final SparseArray<UidBatteryConsumer.Builder> uidBatteryConsumerBuilders =
                builder.getUidBatteryConsumerBuilders();
        for (int i = uidBatteryConsumerBuilders.size() - 1; i >= 0; i--) {
            final UidBatteryConsumer.Builder app = uidBatteryConsumerBuilders.valueAt(i);
            calculateApp(result, app.getBatteryStatsUid(), rawRealtimeUs,
                    BatteryStats.STATS_SINCE_CHARGED);
            app.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK, result.durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK, result.powerMah);
            totalAppDurationMs += result.durationMs;

            if (app.getUid() == Process.ROOT_UID) {
                osBatteryConsumer = app;
                osDurationMs = result.durationMs;
                osPowerMah = result.powerMah;
            }
        }

        // The device has probably been awake for longer than the screen on
        // time and application wake lock time would account for.  Assign
        // this remainder to the OS, if possible.
        if (osBatteryConsumer != null) {
            calculateRemaining(result, batteryStats, rawRealtimeUs, rawUptimeUs,
                    BatteryStats.STATS_SINCE_CHARGED, osPowerMah, osDurationMs, totalAppDurationMs);
            osBatteryConsumer.setUsageDurationMillis(BatteryConsumer.POWER_COMPONENT_WAKELOCK,
                    result.durationMs)
                    .setConsumedPower(BatteryConsumer.POWER_COMPONENT_WAKELOCK, result.powerMah);
        }
    }

    @Override
    public void calculate(List<BatterySipper> sippers, BatteryStats batteryStats,
            long rawRealtimeUs, long rawUptimeUs, int statsType, SparseArray<UserHandle> asUsers) {
        final PowerAndDuration result = new PowerAndDuration();
        BatterySipper osSipper = null;
        double osPowerMah = 0;
        long osDurationMs = 0;
        long totalAppDurationMs = 0;
        for (int i = sippers.size() - 1; i >= 0; i--) {
            final BatterySipper app = sippers.get(i);
            if (app.drainType == BatterySipper.DrainType.APP) {
                calculateApp(result, app.uidObj, rawRealtimeUs, statsType);
                app.wakeLockTimeMs = result.durationMs;
                app.wakeLockPowerMah = result.powerMah;
                totalAppDurationMs += result.durationMs;

                if (app.getUid() == Process.ROOT_UID) {
                    osSipper = app;
                    osPowerMah = result.powerMah;
                    osDurationMs = result.durationMs;
                }
            }
        }

        // The device has probably been awake for longer than the screen on
        // time and application wake lock time would account for.  Assign
        // this remainder to the OS, if possible.
        if (osSipper != null) {
            calculateRemaining(result, batteryStats, rawRealtimeUs, rawUptimeUs, statsType,
                    osPowerMah, osDurationMs, totalAppDurationMs);
            osSipper.wakeLockTimeMs = result.durationMs;
            osSipper.wakeLockPowerMah = result.powerMah;
            osSipper.sumPower();
        }
    }

    private void calculateApp(PowerAndDuration result, BatteryStats.Uid u, long rawRealtimeUs,
            int statsType) {
        long wakeLockTimeUs = 0;
        final ArrayMap<String, ? extends BatteryStats.Uid.Wakelock> wakelockStats =
                u.getWakelockStats();
        final int wakelockStatsCount = wakelockStats.size();
        for (int i = 0; i < wakelockStatsCount; i++) {
            final BatteryStats.Uid.Wakelock wakelock = wakelockStats.valueAt(i);

            // Only care about partial wake locks since full wake locks
            // are canceled when the user turns the screen off.
            BatteryStats.Timer timer = wakelock.getWakeTime(BatteryStats.WAKE_TYPE_PARTIAL);
            if (timer != null) {
                wakeLockTimeUs += timer.getTotalTimeLocked(rawRealtimeUs, statsType);
            }
        }
        result.durationMs = wakeLockTimeUs / 1000; // convert to millis

        // Add cost of holding a wake lock.
        result.powerMah = mPowerEstimator.calculatePower(result.durationMs);
        if (DEBUG && result.powerMah != 0) {
            Log.d(TAG, "UID " + u.getUid() + ": wake " + result.durationMs
                    + " power=" + formatCharge(result.powerMah));
        }
    }

    private void calculateRemaining(PowerAndDuration result, BatteryStats stats, long rawRealtimeUs,
            long rawUptimeUs, int statsType, double osPowerMah, long osDurationMs,
            long totalAppDurationMs) {
        final long wakeTimeMillis = stats.getBatteryUptime(rawUptimeUs) / 1000
                - stats.getScreenOnTime(rawRealtimeUs, statsType) / 1000
                - totalAppDurationMs;
        if (wakeTimeMillis > 0) {
            final double power = mPowerEstimator.calculatePower(wakeTimeMillis);
            if (DEBUG) {
                Log.d(TAG, "OS wakeLockTime " + wakeTimeMillis + " power " + formatCharge(power));
            }
            result.durationMs = osDurationMs + wakeTimeMillis;
            result.powerMah = osPowerMah + power;
        }
    }
}
