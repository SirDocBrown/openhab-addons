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

import static org.openhab.binding.inkbird.internal.InkbirdBindingConstants.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import javax.measure.quantity.Temperature;
import javax.measure.quantity.Time;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Maps the thermostat and cooker data points documented by Home Assistant's
 * <a href=
 * "https://github.com/make-all/tuya-local/tree/a1e4f407fc158c764f594fe418a482a7bb31f2e5/custom_components/tuya_local/devices">Tuya
 * Local device profiles</a>
 * (inkbird_itc308_thermostat.yaml and inkbird_sousvide_cooker.yaml).
 * See the <a href="https://community.home-assistant.io/t/357977">ISV-100W compatibility report</a> and
 * <a href="https://github.com/make-all/tuya-local/issues/1416">cooker temperature unit investigation</a>.
 * <a href="https://tasmota.github.io/docs/TuyaMCU/#inkbird-itc-308-wifi">Tasmota's ITC-308 notes</a>
 * also document numeric relay state codes.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
enum InkbirdProfile {
    THERMOSTAT(Set.of("101", "104", "106", "111", "112", "113", "115", "116")),
    COOKER(Set.of("101", "102", "103", "104", "105", "106", "107", "108", "109", "110"));

    private final Logger logger = LoggerFactory.getLogger(InkbirdProfile.class);
    private static final Map<String, String> RELAY_STATES = Map.of("0", "idle", "1", "cooling", "2", "idle", "3",
            "heating");
    private final Set<String> points;

    InkbirdProfile(Set<String> points) {
        this.points = points;
    }

    Set<String> dataPoints() {
        return points;
    }

    boolean matches(JsonObject dps) {
        if (!numeric(dps.get("104")) || !numeric(dps.get(this == COOKER ? "103" : "106"))) {
            return false;
        }
        var discriminator = dps.get("101");
        return discriminator != null && discriminator.isJsonPrimitive() && (this == COOKER
                ? discriminator.getAsJsonPrimitive().isBoolean()
                : discriminator.getAsJsonPrimitive().isString()
                        && ("C".equals(discriminator.getAsString()) || "F".equals(discriminator.getAsString())));
    }

    void publish(JsonObject dps, BiConsumer<String, State> update) {
        boolean fahrenheit = this == THERMOSTAT && "F".equals(dps.get("101").getAsString());
        update.accept(CHANNEL_TEMPERATURE, temperature(dps.get(fahrenheit ? "116" : "104"), fahrenheit));
        update.accept(CHANNEL_TARGET_TEMPERATURE, temperature(dps.get(this == COOKER ? "103" : "106"), fahrenheit));
        if (this == COOKER) {
            update.accept(CHANNEL_RUNNING, OnOffType.from(dps.get("101").getAsBoolean()));
            update.accept(CHANNEL_OPERATING_STATE, string(dps.get("102")));
            update.accept(CHANNEL_COOKING_TIME, duration(dps.get("105")));
            update.accept(CHANNEL_REMAINING_TIME, duration(dps.get("106")));
            update.accept(CHANNEL_FAULT_CODE, number(dps.get("107")));
        } else {
            var relay = dps.get("115");
            State raw = numeric(relay) ? number(relay) : string(relay);
            String state = RELAY_STATES.get(raw.toString());
            update.accept(CHANNEL_OPERATING_STATE, state == null ? UnDefType.UNDEF : new StringType(state));
            update.accept(CHANNEL_HIGH_ALARM, flag(dps.get("111")));
            update.accept(CHANNEL_LOW_ALARM, flag(dps.get("112")));
            update.accept(CHANNEL_PROBE_FAULT, flag(dps.get("113")));
        }
    }

    JsonObject encode(String channel, Command command, JsonObject status) {
        var changes = new JsonObject();
        if (!matches(status)) {
            return changes;
        }
        if (CHANNEL_RUNNING.equals(channel) && this == COOKER && command instanceof OnOffType power) {
            changes.addProperty("101", power == OnOffType.ON);
        } else if (CHANNEL_TARGET_TEMPERATURE.equals(channel)) {
            boolean fahrenheit = this == THERMOSTAT && "F".equals(status.get("101").getAsString());
            var unit = fahrenheit ? ImperialUnits.FAHRENHEIT : SIUnits.CELSIUS;
            BigDecimal value;
            if (command instanceof QuantityType<?> quantity) {
                var converted = quantity.toUnit(unit);
                if (converted == null) {
                    return unsupported(channel, command);
                }
                value = converted.toBigDecimal();
            } else if (command instanceof DecimalType decimal) {
                value = decimal.toBigDecimal();
            } else {
                return unsupported(channel, command);
            }
            int minimum = this == COOKER ? 250 : fahrenheit ? -580 : -500;
            int maximum = this == COOKER ? 960 : fahrenheit ? 2100 : 999;
            BigDecimal scaled = value.movePointRight(1);
            if (scaled.compareTo(BigDecimal.valueOf(minimum)) >= 0
                    && scaled.compareTo(BigDecimal.valueOf(maximum)) <= 0) {
                changes.addProperty(this == COOKER ? "103" : "106",
                        scaled.setScale(0, RoundingMode.HALF_UP).intValueExact());
            } else {
                logger.warn("Rejected {} on {}: allowed range is {} to {} {}", command, channel,
                        BigDecimal.valueOf(minimum, 1), BigDecimal.valueOf(maximum, 1), unit);
                return changes;
            }
        } else if (CHANNEL_COOKING_TIME.equals(channel) && this == COOKER) {
            BigDecimal minutes;
            if (command instanceof QuantityType<?> quantity) {
                var converted = quantity.toUnit(Units.MINUTE);
                if (converted == null) {
                    return unsupported(channel, command);
                }
                minutes = converted.toBigDecimal();
            } else if (command instanceof DecimalType decimal) {
                minutes = decimal.toBigDecimal();
            } else {
                return unsupported(channel, command);
            }
            if (minutes.signum() >= 0 && minutes.compareTo(BigDecimal.valueOf(5999)) <= 0
                    && minutes.stripTrailingZeros().scale() <= 0) {
                changes.addProperty("105", minutes.intValueExact());
            } else {
                logger.warn("Rejected {} on {}: allowed range is 0 to 5999 whole minutes", command, channel);
                return changes;
            }
        } else {
            return unsupported(channel, command);
        }
        return changes;
    }

    private JsonObject unsupported(String channel, Command command) {
        logger.debug("Ignored unsupported {} command on channel {}", command.getClass().getSimpleName(), channel);
        return new JsonObject();
    }

    private static State temperature(@Nullable JsonElement raw, boolean fahrenheit) {
        return raw != null && numeric(raw) ? new QuantityType<Temperature>(raw.getAsBigDecimal().movePointLeft(1),
                fahrenheit ? ImperialUnits.FAHRENHEIT : SIUnits.CELSIUS) : UnDefType.UNDEF;
    }

    private static State duration(@Nullable JsonElement raw) {
        return raw != null && numeric(raw) ? new QuantityType<Time>(raw.getAsBigDecimal(), Units.MINUTE)
                : UnDefType.UNDEF;
    }

    private static State number(@Nullable JsonElement raw) {
        return raw != null && numeric(raw) ? new DecimalType(raw.getAsBigDecimal()) : UnDefType.UNDEF;
    }

    private static boolean numeric(@Nullable JsonElement raw) {
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isNumber();
    }

    private static State string(@Nullable JsonElement raw) {
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()
                ? new StringType(raw.getAsString())
                : UnDefType.UNDEF;
    }

    private static State flag(@Nullable JsonElement raw) {
        return raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isBoolean()
                ? OnOffType.from(raw.getAsBoolean())
                : UnDefType.UNDEF;
    }
}
