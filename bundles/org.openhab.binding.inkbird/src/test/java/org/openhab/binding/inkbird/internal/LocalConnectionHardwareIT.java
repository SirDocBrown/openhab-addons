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

import java.util.HashSet;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Reads appliance status using the environment of the test process.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@EnabledIfEnvironmentVariable(named = "INKBIRD_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INKBIRD_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INKBIRD_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INKBIRD_VERSION", matches = ".+")
@NonNullByDefault
class LocalConnectionHardwareIT {
    @Test
    void readsStatus() throws Exception {
        String version = Objects.requireNonNull(System.getenv("INKBIRD_VERSION"));
        assertTrue("3.3".equals(version) || "3.4".equals(version));
        var points = new HashSet<>(InkbirdProfile.THERMOSTAT.dataPoints());
        points.addAll(InkbirdProfile.COOKER.dataPoints());
        try (var connection = new LocalConnection(Objects.requireNonNull(System.getenv("INKBIRD_HOST")),
                InkbirdBindingConstants.DEFAULT_PORT, Objects.requireNonNull(System.getenv("INKBIRD_ID")),
                Objects.requireNonNull(System.getenv("INKBIRD_KEY")), "3.4".equals(version))) {
            var status = connection.query(points);
            org.slf4j.LoggerFactory.getLogger(LocalConnectionHardwareIT.class).debug("Inkbird DPS: {}", status);
            assertTrue(InkbirdProfile.THERMOSTAT.matches(status) || InkbirdProfile.COOKER.matches(status));
        }
    }
}
