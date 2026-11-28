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

import java.util.HexFormat;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests the public discovery wire format.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
class InkbirdDiscoveryTest {
    @Test
    void mapsBothProductsAndRejectsDamagedAdvertisements() throws Exception {
        byte[] thermostat = HexFormat.of().parseHex(
                "000055aa00000000000000130000005c0000000076dee6d9328d64dd7dfa7c7ec33ee3587dcded781c0494d263b1f894cf52f652337bfb2375ef91ed4eafabf289faf8f10d5d65d478fbc1ce2e107937710f023ace080ca77cc5b9329cb30547032eaef1483eaa3e0000aa55");
        byte[] cooker = HexFormat.of().parseHex(
                "000055aa000000000000000000000050000000007b2270726f647563744b6579223a22695069716b4d5047326c4f5574707142222c2267774964223a226578616d706c653432222c2276657273696f6e223a22332e33227dbd0729e90000aa55");
        var first = InkbirdDiscovery.decode(thermostat, "192.0.2.1");
        var second = InkbirdDiscovery.decode(cooker, "192.0.2.2");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("inkbird:itc-308-wifi:example42", first.getThingUID().toString());
        assertEquals("3.4", first.getProperties().get("protocolVersion"));
        assertEquals("192.0.2.2", second.getProperties().get("hostname"));
        assertEquals("inkbird:isv-100w:example42", second.getThingUID().toString());
        assertEquals("Inkbird ITC-308-WIFI", first.getLabel());
        assertEquals("Inkbird ISV-100W", second.getLabel());
        assertFalse(first.getProperties().containsKey("localKey"));
        thermostat[20] ^= 1;
        assertNull(InkbirdDiscovery.decode(thermostat, "192.0.2.1"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void sharedListenersReceiveLoopbackBroadcast() throws Exception {
        try (var first = java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET);
                var second = java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET);
                var sender = new java.net.DatagramSocket()) {
            InkbirdDiscovery.bind(first, 0);
            int port = first.socket().getLocalPort();
            InkbirdDiscovery.bind(second, port);
            sender.setBroadcast(true);
            byte[] marker = { 42 };
            sender.send(new java.net.DatagramPacket(marker, marker.length,
                    java.net.InetAddress.getByName("127.255.255.255"), port));
            for (var listener : java.util.List.of(first, second)) {
                listener.socket().setSoTimeout(2000);
                var packet = new java.net.DatagramPacket(new byte[1], 1);
                listener.socket().receive(packet);
                assertArrayEquals(marker, packet.getData());
            }
        }
    }
}
