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

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests unit-aware command generation and model detection.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class InkbirdProfileTest {
    private static JsonObject status(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void thermostatUsesSeparateFahrenheitSensorAndConvertsSetpoint() {
        var data = status("{\"101\":\"F\",\"104\":200,\"116\":680,\"106\":700,\"115\":\"3\"}");
        var states = new HashMap<String, State>();
        InkbirdProfile.THERMOSTAT.publish(data, states::put);
        assertEquals(new QuantityType<>("68 °F"), states.get("temperature"));
        assertEquals(new StringType("heating"), states.get("operating-state"));
        assertEquals(UnDefType.UNDEF, states.get("probe-fault"));
        assertEquals(680, InkbirdProfile.THERMOSTAT.encode("target-temperature", new QuantityType<>("20 °C"), data)
                .get("106").getAsInt());
    }

    @Test
    void thermostatHandlesNumericRelayAndMissingMeasurements() {
        var data = status("{\"101\":\"C\",\"104\":205,\"106\":220,\"115\":1}");
        var states = new HashMap<String, State>();
        InkbirdProfile.THERMOSTAT.publish(data, states::put);
        assertEquals(new StringType("cooling"), states.get("operating-state"));
        assertEquals(new QuantityType<>("20.5 °C"), states.get("temperature"));
        data.remove("104");
        assertFalse(InkbirdProfile.THERMOSTAT.matches(data));
        assertTrue(InkbirdProfile.THERMOSTAT.encode("target-temperature", new DecimalType(22), data).isEmpty());
    }

    @Test
    void cookerUsesCelsiusRegardlessOfDisplayAndKeepsStartSeparate() {
        var data = status("{\"101\":false,\"108\":false,\"103\":550,\"104\":510}");
        var states = new HashMap<String, State>();
        InkbirdProfile.COOKER.publish(data, states::put);
        assertEquals(new QuantityType<>("51 °C"), states.get("temperature"));
        assertEquals(status("{\"105\":90}"),
                InkbirdProfile.COOKER.encode("cooking-time", new QuantityType<>("1.5 h"), data));
        assertEquals(status("{\"103\":550}"),
                InkbirdProfile.COOKER.encode("target-temperature", new QuantityType<>("131 °F"), data));
        assertEquals(status("{\"101\":true}"), InkbirdProfile.COOKER.encode("running", OnOffType.ON, data));
    }

    @Test
    void refusesMismatchedProfilesAndOutOfRangeCommands() {
        var data = status("{\"101\":true,\"103\":550,\"104\":530}");
        assertFalse(InkbirdProfile.THERMOSTAT.matches(data));
        assertFalse(InkbirdProfile.COOKER.matches(status("{\"101\":\"F\"}")));
        assertTrue(InkbirdProfile.COOKER.encode("target-temperature", new QuantityType<>("100 °C"), data).isEmpty());
        assertTrue(InkbirdProfile.COOKER.encode("cooking-time", new DecimalType("0.5"), data).isEmpty());
        assertTrue(InkbirdProfile.COOKER.encode("cooking-time", new DecimalType(6000), data).isEmpty());
        assertTrue(InkbirdProfile.COOKER.encode("running", OnOffType.ON, new JsonObject()).isEmpty());
    }

    @Test
    void thermostatHardwareSnapshot() {
        var data = status(
                """
                        {"12":0,"101":"C","102":0,"104":225,"106":980,"108":0,"109":1000,"110":-400,"111":false,"112":false,"113":false,"115":3,"116":725,"117":20,"118":20}
                        """);
        assertTrue(InkbirdProfile.THERMOSTAT.matches(data));
        var states = new HashMap<String, State>();
        InkbirdProfile.THERMOSTAT.publish(data, states::put);
        assertEquals(new QuantityType<>("22.5 °C"), states.get("temperature"));
        assertEquals(new QuantityType<>("98 °C"), states.get("target-temperature"));
        assertEquals(new StringType("heating"), states.get("operating-state"));
        assertEquals(OnOffType.OFF, states.get("high-alarm"));
        assertEquals(OnOffType.OFF, states.get("low-alarm"));
        assertEquals(OnOffType.OFF, states.get("probe-fault"));
    }

    @Test
    void cookerHardwareSnapshot() {
        var data = status("""
                {"101":false,"102":"stop","103":520,"104":207,"105":304,"106":304,"107":3,"108":true,"109":0,"110":0}
                """);
        assertTrue(InkbirdProfile.COOKER.matches(data));
        var states = new HashMap<String, State>();
        InkbirdProfile.COOKER.publish(data, states::put);
        assertEquals(new QuantityType<>("20.7 °C"), states.get("temperature"));
        assertEquals(new QuantityType<>("52 °C"), states.get("target-temperature"));
        assertEquals(new StringType("stop"), states.get("operating-state"));
        assertEquals(OnOffType.OFF, states.get("running"));
        assertEquals(new QuantityType<>("304 min"), states.get("cooking-time"));
        assertEquals(new QuantityType<>("304 min"), states.get("remaining-time"));
        assertEquals(new DecimalType(3), states.get("fault-code"));
    }
}
