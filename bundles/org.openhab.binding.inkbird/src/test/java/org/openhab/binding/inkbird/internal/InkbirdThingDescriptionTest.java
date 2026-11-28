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
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.openhab.core.thing.xml.internal.ThingDescriptionReader;
import org.w3c.dom.Element;

/**
 * Checks that the Thing metadata is readable and uses tags supported by earlier 5.3 snapshots.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class InkbirdThingDescriptionTest {
    @Test
    void readsThingDescriptionsWithCompatibleSemanticTags() throws Exception {
        var xml = Objects.requireNonNull(getClass().getResource("/OH-INF/thing/thing-types.xml"));
        assertFalse(Objects.requireNonNull(new ThingDescriptionReader().readFromXML(xml)).isEmpty());
        try (var stream = xml.openStream()) {
            var descriptions = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
            var groups = descriptions.getElementsByTagName("tags");
            assertTrue(groups.getLength() > 0);
            for (int i = 0; i < groups.getLength(); i++) {
                var group = (Element) groups.item(i);
                assertEquals(group.getElementsByTagName("*").getLength(),
                        group.getElementsByTagName("tag").getLength());
            }
        }
    }

    @Test
    void declaresAllChannelsAndTheirTypes() throws Exception {
        var expected = Map.of("itc-308-wifi",
                Set.of("temperature", "target-temperature", "operating-state", "high-alarm", "low-alarm",
                        "probe-fault"),
                "isv-100w", Set.of("temperature", "target-temperature", "operating-state", "running", "cooking-time",
                        "remaining-time", "fault-code"));
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/OH-INF/thing/thing-types.xml"))) {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream);
            var types = new HashSet<String>();
            var definitions = document.getElementsByTagName("channel-type");
            for (int i = 0; i < definitions.getLength(); i++) {
                assertTrue(types.add(((Element) definitions.item(i)).getAttribute("id")));
            }
            var things = document.getElementsByTagName("thing-type");
            var found = new HashSet<String>();
            for (int i = 0; i < things.getLength(); i++) {
                var thing = (Element) things.item(i);
                String id = thing.getAttribute("id");
                assertTrue(found.add(id), id);
                var channels = thing.getElementsByTagName("channel");
                var ids = new HashSet<String>();
                for (int j = 0; j < channels.getLength(); j++) {
                    var channel = (Element) channels.item(j);
                    assertTrue(ids.add(channel.getAttribute("id")), id);
                    String type = channel.getAttribute("typeId");
                    assertTrue(types.contains(type), id + " references missing channel type " + type);
                }
                assertEquals(expected.get(id), ids, id);
            }
            assertEquals(expected.keySet(), found);
        }
    }

    @Test
    void requiresLocalKeyOnDiscoveredThings() {
        var config = new InkbirdConfiguration();
        config.hostname = "localhost";
        config.deviceId = "test-device";
        assertEquals("key", InkbirdHandler.configurationError(config));
        config.localKey = "short";
        assertEquals("key-length", InkbirdHandler.configurationError(config));
        config.localKey = "0123456789abcdef";
        assertNull(InkbirdHandler.configurationError(config));
    }
}
