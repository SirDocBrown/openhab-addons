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
package org.openhab.binding.inkbird.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonParseException;

/**
 * Polls an Inkbird appliance and serializes commands with status reads.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public class InkbirdHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(InkbirdHandler.class);
    private final InkbirdProfile profile;
    private final Object exchangeLock = new Object();
    private boolean explicitPoints;
    private @Nullable InkbirdConfiguration configuration;
    private @Nullable ScheduledFuture<?> poll;

    public InkbirdHandler(Thing thing, InkbirdProfile profile) {
        super(thing);
        this.profile = profile;
    }

    @Override
    public void initialize() {
        synchronized (exchangeLock) {
            stopPolling();
            var config = getConfigAs(InkbirdConfiguration.class);
            String error = configurationError(config);
            if (error != null) {
                updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline." + error);
                return;
            }
            explicitPoints = false;
            configuration = config;
            updateStatus(ThingStatus.UNKNOWN);
            poll = scheduler.scheduleWithFixedDelay(this::poll, 0, config.refreshInterval, TimeUnit.SECONDS);
        }
    }

    static @Nullable String configurationError(InkbirdConfiguration config) {
        if (config.hostname.isBlank()) {
            return "host";
        }
        if (config.deviceId.isBlank()) {
            return "device-id";
        }
        if (config.localKey.isEmpty()) {
            return "key";
        }
        if (config.localKey.getBytes(StandardCharsets.UTF_8).length != 16) {
            return "key-length";
        }
        if (!("3.3".equals(config.protocolVersion) || "3.4".equals(config.protocolVersion))) {
            return "protocol";
        }
        return config.refreshInterval < 5 ? "interval" : null;
    }

    private void poll() {
        try {
            exchange(null, RefreshType.REFRESH);
        } catch (RuntimeException e) {
            logger.warn("Unexpected Inkbird poll failure for {}", thing.getUID(), e);
            updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/offline.communication");
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        exchange(channelUID.getId(), command);
    }

    private void exchange(@Nullable String channel, Command command) {
        synchronized (exchangeLock) {
            var config = configuration;
            if (config == null) {
                return;
            }
            try (var connection = new LocalConnection(config.hostname, InkbirdBindingConstants.DEFAULT_PORT,
                    config.deviceId, config.localKey, "3.4".equals(config.protocolVersion))) {
                var status = connection.query(profile.dataPoints(), explicitPoints);
                if (!profile.matches(status)) {
                    updateStatus(ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.profile");
                    return;
                }
                if (channel != null && !(command instanceof RefreshType)) {
                    var changes = profile.encode(channel, command, status);
                    if (!changes.isEmpty()) {
                        connection.write(changes);
                        status = connection.query(profile.dataPoints());
                        if (!profile.matches(status)) {
                            throw new IOException("Incomplete status after write");
                        }
                    }
                }
                explicitPoints = connection.usesExplicitPoints();
                profile.publish(status, this::updateState);
                updateStatus(ThingStatus.ONLINE);
            } catch (java.io.InterruptedIOException e) {
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/offline.communication");
            } catch (IOException | GeneralSecurityException | JsonParseException | IllegalArgumentException e) {
                logger.debug("Inkbird exchange failed for {}: {}", thing.getUID(), e.getMessage());
                updateStatus(ThingStatusDetail.COMMUNICATION_ERROR, "@text/offline.communication");
            }
        }
    }

    private void stopPolling() {
        configuration = null;
        var job = poll;
        if (job != null) {
            job.cancel(false);
            poll = null;
        }
    }

    @Override
    public void dispose() {
        synchronized (exchangeLock) {
            stopPolling();
            super.dispose();
        }
    }
}
