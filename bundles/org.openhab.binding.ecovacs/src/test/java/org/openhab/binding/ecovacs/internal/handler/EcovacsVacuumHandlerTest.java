/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.ecovacs.internal.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openhab.binding.ecovacs.internal.EcovacsDynamicStateDescriptionProvider;
import org.openhab.binding.ecovacs.internal.api.EcovacsApiException;
import org.openhab.binding.ecovacs.internal.api.EcovacsDevice;
import org.openhab.binding.ecovacs.internal.api.commands.GetChargeStateCommand;
import org.openhab.binding.ecovacs.internal.api.commands.GetCleanStateCommand;
import org.openhab.binding.ecovacs.internal.api.commands.GetComponentLifeSpanCommand;
import org.openhab.binding.ecovacs.internal.api.commands.GetContinuousCleaningCommand;
import org.openhab.binding.ecovacs.internal.api.commands.GetTotalStatsCommand;
import org.openhab.binding.ecovacs.internal.api.commands.GetTotalStatsCommand.TotalStats;
import org.openhab.binding.ecovacs.internal.api.commands.GetWorkStateCommand;
import org.openhab.binding.ecovacs.internal.api.commands.ResumeCleaningCommand;
import org.openhab.binding.ecovacs.internal.api.impl.ProtocolVersion;
import org.openhab.binding.ecovacs.internal.api.impl.dto.response.deviceapi.json.WorkStateReport;
import org.openhab.binding.ecovacs.internal.api.model.ChargeMode;
import org.openhab.binding.ecovacs.internal.api.model.CleanLogRecord;
import org.openhab.binding.ecovacs.internal.api.model.CleanMode;
import org.openhab.binding.ecovacs.internal.api.model.DeviceCapability;
import org.openhab.core.i18n.LocaleProvider;
import org.openhab.core.i18n.TranslationProvider;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.Gson;

/**
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class EcovacsVacuumHandlerTest {
    private final EcovacsDevice device = mock(EcovacsDevice.class);
    private final Clock clock = mock(Clock.class);
    private final CapturingHandler handler = new CapturingHandler(clock);
    private final Gson gson = new Gson();

    @BeforeEach
    void setup() throws Exception {
        when(device.hasCapability(DeviceCapability.WORK_STATE_REPORTING)).thenReturn(true);
        when(device.sendCommand(any(GetChargeStateCommand.class))).thenReturn(ChargeMode.IDLE);
    }

    @AfterEach
    void dispose() {
        handler.dispose();
    }

    @Test
    void initializesFromWorkStateWithoutUnsupportedCleanQuery() throws Exception {
        workState("{\"robotState\":{\"state\":\"cleaning\",\"cleanState\":{\"type\":\"auto\"}}}");
        handler.fetchInitialStateAndCommandValues(device);
        verify(device).sendCommand(any(GetWorkStateCommand.class));
        verify(device, never()).sendCommand(any(GetCleanStateCommand.class));
        assertEquals(new StringType("cleaning"), handler.states.get("status#state"));
        assertEquals(new StringType("auto"), handler.states.get("status#current-cleaning-mode"));
    }

    @Test
    void unsupportedInitialWorkQueryWaitsForMqtt() throws Exception {
        when(device.sendCommand(any(GetWorkStateCommand.class))).thenThrow(new EcovacsApiException("unavailable"));
        assertDoesNotThrow(() -> handler.fetchInitialStateAndCommandValues(device));
        assertFalse(handler.states.containsKey("status#state"));
        handler.onCleaningModeUpdated(device, CleanMode.AUTO, Optional.empty());
        assertEquals(new StringType("cleaning"), handler.states.get("status#state"));
    }

    @Test
    void chargingRemainsVisibleIfInitialWorkQueryIsUnavailable() throws Exception {
        when(device.sendCommand(any(GetChargeStateCommand.class))).thenReturn(ChargeMode.CHARGING);
        when(device.sendCommand(any(GetWorkStateCommand.class))).thenThrow(new EcovacsApiException("unavailable"));
        handler.fetchInitialStateAndCommandValues(device);
        assertEquals(new StringType("charging"), handler.states.get("status#state"));
        assertEquals(new StringType("charge"), handler.states.get("actions#command"));
        handler.onCleaningModeUpdated(device, CleanMode.AUTO, Optional.empty());
        assertEquals(new StringType("cleaning"), handler.states.get("status#state"));
    }

    @ParameterizedTest
    @EnumSource(value = CleanMode.class, names = { "AUTO", "PAUSE", "RETURNING" })
    void activeWorkStateOverridesStaleChargingReport(CleanMode mode) throws Exception {
        when(device.sendCommand(any(GetChargeStateCommand.class))).thenReturn(ChargeMode.CHARGING);
        workState("{}");
        handler.fetchInitialStateAndCommandValues(device);
        handler.onCleaningModeUpdated(device, mode, Optional.empty());
        assertEquals(
                new StringType(mode == CleanMode.AUTO ? "cleaning" : mode == CleanMode.PAUSE ? "pause" : "returning"),
                handler.states.get("status#state"));
    }

    @Test
    void stationWorkStateIsVisibleAfterInitialization() throws Exception {
        when(device.sendCommand(any(GetChargeStateCommand.class))).thenReturn(ChargeMode.CHARGING);
        workState("{\"robotState\":{\"state\":\"idle\"},\"stationState\":{\"state\":\"washing\"}}");
        handler.fetchInitialStateAndCommandValues(device);
        assertEquals(new StringType("washing"), handler.states.get("status#state"));
        assertEquals(UnDefType.UNDEF, handler.states.get("status#current-cleaning-mode"));
    }

    @Test
    void interruptionDuringInitialWorkQueryIsPropagated() throws Exception {
        when(device.sendCommand(any(GetWorkStateCommand.class))).thenThrow(new InterruptedException("cancelled"));
        assertThrows(InterruptedException.class, () -> handler.fetchInitialStateAndCommandValues(device));
    }

    @Test
    void olderProfilesRetainTheirInitialQueryAndChargingInterpretation() throws Exception {
        when(device.hasCapability(DeviceCapability.WORK_STATE_REPORTING)).thenReturn(false);
        when(device.sendCommand(any(GetChargeStateCommand.class))).thenReturn(ChargeMode.CHARGING);
        when(device.sendCommand(any(GetCleanStateCommand.class))).thenReturn(CleanMode.AUTO);
        handler.fetchInitialStateAndCommandValues(device);
        verify(device).sendCommand(any(GetCleanStateCommand.class));
        verify(device, never()).sendCommand(any(GetWorkStateCommand.class));
        assertEquals(new StringType("charging"), handler.states.get("status#state"));
        handler.onCleaningModeUpdated(device, CleanMode.PAUSE, Optional.empty());
        assertEquals(new StringType("charging"), handler.states.get("status#state"));
    }

    @Test
    void twoPollsWithoutImageAttemptOneDownloadAndNewRecordRetries() throws Exception {
        when(device.hasCapability(DeviceCapability.LEGACY_CLEAN_MAP_FALLBACK)).thenReturn(true);
        var record = new CleanLogRecord(1700000000L, 120, 10, Optional.of("https://example.invalid/map"),
                CleanMode.AUTO);
        when(device.hasCapability(DeviceCapability.EXTENDED_CLEAN_LOG_RECORD)).thenReturn(true);
        when(device.hasCapability(DeviceCapability.MAPPING)).thenReturn(true);
        when(device.sendCommand(any(GetTotalStatsCommand.class)))
                .thenReturn(Objects.requireNonNull(gson.fromJson("{}", TotalStats.class)));
        when(device.sendCommand(any(GetContinuousCleaningCommand.class))).thenReturn(false);
        when(device.sendCommand(any(GetComponentLifeSpanCommand.class))).thenReturn(100);
        when(device.getCleanLogs()).thenReturn(List.of(record));
        when(device.downloadCleanMapImage(any())).thenReturn(Optional.empty());
        var field = EcovacsVacuumHandler.class.getDeclaredField("device");
        field.setAccessible(true);
        field.set(handler, device);
        var poll = EcovacsVacuumHandler.class.getDeclaredMethod("pollData");
        poll.setAccessible(true);
        poll.invoke(handler);
        poll.invoke(handler);
        verify(device, times(1)).downloadCleanMapImage(record);
        assertEquals(UnDefType.NULL, handler.states.get("last-clean#last-clean-map"));
        var next = new CleanLogRecord(1700001000L, 60, 5, record.mapImageUrl, CleanMode.AUTO);
        when(device.getCleanLogs()).thenReturn(List.of(next));
        poll.invoke(handler);
        verify(device).downloadCleanMapImage(next);
    }

    @Test
    void olderMappingProfilesRetryEmptyImagesAndStopOnlyAfterSuccess() throws Exception {
        var record = prepareImagePoll();
        when(device.downloadCleanMapImage(record)).thenReturn(Optional.empty(), Optional.of(new byte[] { 1 }));
        poll();
        poll();
        poll();
        verify(device, times(2)).downloadCleanMapImage(record);
    }

    @Test
    void fallbackProfilesRetryTransientFailuresOnlyAfterThirtyMinutes() throws Exception {
        when(device.hasCapability(DeviceCapability.LEGACY_CLEAN_MAP_FALLBACK)).thenReturn(true);
        var record = prepareImagePoll();
        when(device.downloadCleanMapImage(record)).thenThrow(new EcovacsApiException(new TimeoutException("test")))
                .thenReturn(Optional.empty());
        when(clock.millis()).thenReturn(1000L);
        poll();
        poll();
        when(clock.millis()).thenReturn(1000L + Duration.ofMinutes(30).toMillis() - 1);
        poll();
        verify(device, times(1)).downloadCleanMapImage(record);
        when(clock.millis()).thenReturn(1000L + Duration.ofMinutes(30).toMillis());
        poll();
        verify(device, times(2)).downloadCleanMapImage(record);
        when(clock.millis()).thenReturn(1000L + Duration.ofHours(2).toMillis());
        poll();
        verify(device, times(2)).downloadCleanMapImage(record);
    }

    @Test
    void newFallbackRecordDoesNotWaitForPreviousRecordsCooldown() throws Exception {
        when(device.hasCapability(DeviceCapability.LEGACY_CLEAN_MAP_FALLBACK)).thenReturn(true);
        var record = prepareImagePoll();
        when(device.downloadCleanMapImage(record)).thenThrow(new EcovacsApiException("temporary"));
        poll();
        var next = new CleanLogRecord(1700001000L, 60, 5, record.mapImageUrl, CleanMode.AUTO);
        when(device.getCleanLogs()).thenReturn(List.of(next));
        when(device.downloadCleanMapImage(next)).thenReturn(Optional.empty());
        poll();
        verify(device).downloadCleanMapImage(next);
    }

    @Test
    void interruptionDoesNotConsumeFallbackAttempt() throws Exception {
        when(device.hasCapability(DeviceCapability.LEGACY_CLEAN_MAP_FALLBACK)).thenReturn(true);
        var record = prepareImagePoll();
        when(device.downloadCleanMapImage(record)).thenThrow(new InterruptedException("cancelled"))
                .thenReturn(Optional.empty());
        var fetch = EcovacsVacuumHandler.class.getDeclaredMethod("fetchLastCleanMap", EcovacsDevice.class,
                CleanLogRecord.class);
        fetch.setAccessible(true);
        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> fetch.invoke(handler, device, record));
        assertInstanceOf(InterruptedException.class, failure.getCause());
        fetch.invoke(handler, device, record);
        verify(device, times(2)).downloadCleanMapImage(record);
    }

    @ParameterizedTest
    @EnumSource(value = CleanMode.class, names = { "WASHING", "DRYING", "EMPTYING" })
    void stationWorkPreservesJobModeForResumeAndTrueJobEndClearsIt(CleanMode stationMode) throws Exception {
        setDevice();
        var resumed = new CountDownLatch(1);
        var resumedType = new AtomicReference<String>();
        when(device.sendCommand(any(ResumeCleaningCommand.class))).thenAnswer(call -> {
            var command = call.getArgument(0, ResumeCleaningCommand.class);
            resumedType
                    .set(command.getJsonPayload(ProtocolVersion.JSON_V2, gson).getAsJsonObject().getAsJsonObject("body")
                            .getAsJsonObject("data").getAsJsonObject("content").get("type").getAsString());
            resumed.countDown();
            return null;
        });
        report(0, "cleaning", "idle");
        String station = stationMode.name().toLowerCase(java.util.Locale.ROOT);
        report(0, "cleaning", station);
        assertEquals(new StringType(station), handler.states.get("status#state"));
        report(1, "cleaning", station);
        handler.handleCommand(new ChannelUID(handler.getThing().getUID(), "actions#command"), new StringType("resume"));
        assertTrue(resumed.await(3, TimeUnit.SECONDS));
        assertEquals("spotArea", resumedType.get());
        report(0, "idle", station);
        assertEquals(new StringType(station), handler.states.get("status#state"));
        assertEquals(UnDefType.UNDEF, handler.states.get("status#current-cleaning-mode"));
        var mode = EcovacsVacuumHandler.class.getDeclaredField("lastActiveCleanMode");
        mode.setAccessible(true);
        assertNull(mode.get(handler));
    }

    @Test
    void initialStationWorkReportAlsoPreservesActiveJob() throws Exception {
        workState(
                "{\"robotState\":{\"state\":\"cleaning\",\"cleanState\":{\"type\":\"freeClean\"}},\"stationState\":{\"state\":\"washing\"}}");
        handler.fetchInitialStateAndCommandValues(device);
        assertEquals(new StringType("washing"), handler.states.get("status#state"));
        var mode = EcovacsVacuumHandler.class.getDeclaredField("lastActiveCleanMode");
        mode.setAccessible(true);
        assertEquals(CleanMode.SPOT_AREA, mode.get(handler));
    }

    private void report(int paused, String robot, String station) {
        String json = """
                {"paused":%d,"robotState":{"state":"%s","cleanState":{"type":"freeClean"}},
                 "stationState":{"state":"%s"}}
                """.formatted(paused, robot, station);
        if (paused != 0) {
            json = "{\"paused\":1}";
        }
        WorkStateReport report = Objects.requireNonNull(gson.fromJson(json, WorkStateReport.class));
        handler.onCleaningModeUpdated(device, Objects.requireNonNull(report.determineCleanMode(gson, true)),
                Optional.empty(), report.determineActiveJobMode(gson));
    }

    private void setDevice() throws Exception {
        var field = EcovacsVacuumHandler.class.getDeclaredField("device");
        field.setAccessible(true);
        field.set(handler, device);
    }

    private CleanLogRecord prepareImagePoll() throws Exception {
        var record = new CleanLogRecord(1700000000L, 120, 10, Optional.of("https://example.invalid/map"),
                CleanMode.AUTO);
        when(device.hasCapability(DeviceCapability.EXTENDED_CLEAN_LOG_RECORD)).thenReturn(true);
        when(device.hasCapability(DeviceCapability.MAPPING)).thenReturn(true);
        when(device.sendCommand(any(GetTotalStatsCommand.class)))
                .thenReturn(Objects.requireNonNull(gson.fromJson("{}", TotalStats.class)));
        when(device.sendCommand(any(GetContinuousCleaningCommand.class))).thenReturn(false);
        when(device.sendCommand(any(GetComponentLifeSpanCommand.class))).thenReturn(100);
        when(device.getCleanLogs()).thenReturn(List.of(record));
        when(device.downloadCleanMapImage(any())).thenReturn(Optional.empty());
        setDevice();
        return record;
    }

    private void poll() throws Exception {
        var method = EcovacsVacuumHandler.class.getDeclaredMethod("pollData");
        method.setAccessible(true);
        method.invoke(handler);
    }

    private void workState(String json) throws Exception {
        when(device.sendCommand(any(GetWorkStateCommand.class)))
                .thenReturn(Objects.requireNonNull(gson.fromJson(json, WorkStateReport.class)));
    }

    private static class CapturingHandler extends EcovacsVacuumHandler {
        final Map<String, State> states = new HashMap<>();

        CapturingHandler(Clock clock) {
            super(ThingBuilder.create(new ThingTypeUID("ecovacs:vacuum"), new ThingUID("ecovacs:vacuum:test")).build(),
                    mock(TranslationProvider.class), mock(LocaleProvider.class),
                    mock(EcovacsDynamicStateDescriptionProvider.class), clock);
        }

        @Override
        protected void updateState(String channelId, State state) {
            states.put(channelId, state);
        }
    }
}
