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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link InkbirdBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public class InkbirdBindingConstants {

    private static final String BINDING_ID = "inkbird";

    public static final ThingTypeUID ITC_308 = new ThingTypeUID(BINDING_ID, "itc-308-wifi");
    public static final ThingTypeUID ISV_100 = new ThingTypeUID(BINDING_ID, "isv-100w");

    static final int DEFAULT_PORT = 6668;
    static final String CHANNEL_TEMPERATURE = "temperature";
    static final String CHANNEL_TARGET_TEMPERATURE = "target-temperature";
    static final String CHANNEL_RUNNING = "running";
    static final String CHANNEL_OPERATING_STATE = "operating-state";
    static final String CHANNEL_COOKING_TIME = "cooking-time";
    static final String CHANNEL_REMAINING_TIME = "remaining-time";
    static final String CHANNEL_FAULT_CODE = "fault-code";
    static final String CHANNEL_HIGH_ALARM = "high-alarm";
    static final String CHANNEL_LOW_ALARM = "low-alarm";
    static final String CHANNEL_PROBE_FAULT = "probe-fault";

    private InkbirdBindingConstants() {
    }
}
