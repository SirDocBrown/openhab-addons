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

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises an actual loopback socket using independently encoded protocol fixtures.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@Timeout(8)
@NonNullByDefault
class LocalConnectionTest {
    private static final String KEY = "1234567890abcdef";
    private static final String RESPONSE = "000055aa000000010000000a0000002c00000000"
            + "525123c51eb59cc148f983558589ece103d15060adcf57d7bb8836473abaa457fe2a3da00000aa55";

    @Test
    void readsEncryptedStatusAndSendsOnlyTheRequestedChange() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                        "example", KEY);
                var peer = server.accept()) {
            peer.setSoTimeout(3000);
            peer.getOutputStream().write(HexFormat.of().parseHex(RESPONSE));
            assertEquals(215, client.query(InkbirdProfile.THERMOSTAT.dataPoints()).get("104").getAsInt());
            var input = new DataInputStream(peer.getInputStream());
            assertEquals("example", request(input, 10).get("gwId").getAsString());
            var acknowledgement = ByteBuffer.allocate(28);
            acknowledgement.putInt(0x55aa).putInt(2).putInt(7).putInt(12).putInt(0);
            CRC32 crc = new CRC32();
            crc.update(acknowledgement.array(), 0, 20);
            acknowledgement.putInt((int) crc.getValue()).putInt(0xaa55);
            peer.getOutputStream().write(acknowledgement.array());
            var change = new JsonObject();
            change.addProperty("106", 205);
            client.write(change);
            assertEquals(change, request(input, 7).get("dps"));
        }
    }

    @Test
    void rejectsDamagedResponseBeforePublishing() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                        "example", KEY);
                var peer = server.accept()) {
            byte[] damaged = HexFormat.of().parseHex(RESPONSE);
            damaged[23] ^= 1;
            peer.getOutputStream().write(damaged);
            assertThrows(IOException.class, () -> client.query(InkbirdProfile.THERMOSTAT.dataPoints()));
        }
    }

    @Test
    void device22RequestsOnlyProfileDataPoints() throws Exception {
        String id = "0123456789012345678901";
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(), id,
                        KEY);
                var peer = server.accept()) {
            byte[] reply = HexFormat.of().parseHex(RESPONSE);
            ByteBuffer.wrap(reply).putInt(8, 13);
            var crc = new CRC32();
            crc.update(reply, 0, reply.length - 8);
            ByteBuffer.wrap(reply).putInt(reply.length - 8, (int) crc.getValue());
            peer.getOutputStream().write(reply);
            var points = InkbirdProfile.THERMOSTAT.dataPoints();
            assertEquals(215, client.query(points).get("104").getAsInt());
            var query = request(new DataInputStream(peer.getInputStream()), 13);
            assertEquals(id, query.get("uid").getAsString());
            assertEquals(id, query.get("devId").getAsString());
            var dps = query.getAsJsonObject("dps");
            assertEquals(points, dps.keySet());
            points.forEach(dp -> assertTrue(dps.get(dp).isJsonNull()));
        }
    }

    @Test
    void retriesInvalidLegacyQueryWithExplicitPoints() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                        "example", KEY);
                var peer = server.accept()) {
            var output = peer.getOutputStream();
            output.write(reply(1, 10, "json obj data unvalid"));
            output.write(reply(2, 13, "{\"dps\":{\"104\":215}}"));
            var points = InkbirdProfile.THERMOSTAT.dataPoints();
            assertEquals(215, client.query(points).get("104").getAsInt());
            var input = new DataInputStream(peer.getInputStream());
            assertEquals("example", request(input, 10).get("gwId").getAsString());
            assertEquals(points, request(input, 13).getAsJsonObject("dps").keySet());
        }
    }

    // TinyTuya 1.21.1: import tinytuya as t; from unittest.mock import patch
    // d = t.Device("example", "127.0.0.1", "1234567890abcdef", version=3.3)
    // with patch("time.time", return_value=1700000000): print(d._encode_message(d.generate_payload(t.DP_QUERY)).hex())
    @Test
    void legacyVectorAndStatusPushAfterEmptyAcknowledgements() throws Exception {
        var clock = java.time.Clock.fixed(java.time.Instant.ofEpochSecond(1700000000), java.time.ZoneOffset.UTC);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                        "example", KEY, false, clock, new byte[0]);
                var peer = server.accept()) {
            var output = peer.getOutputStream();
            output.write(reply(99, 8, "{\"dps\":{\"104\":999}}"));
            output.write(reply(1, 9, "{\"dps\":{\"104\":999}}"));
            for (int i = 0; i < 5; i++) {
                output.write(reply(1, 10, ""));
            }
            output.write(reply(0, 8,
                    "{\"dps\":{\"101\":false,\"102\":\"stop\",\"103\":520,\"104\":207,\"105\":304,\"106\":304,\"107\":3,\"108\":true,\"109\":0,\"110\":0}}"));
            assertEquals(207, client.query(InkbirdProfile.COOKER.dataPoints()).get("104").getAsInt());
            String expected = "000055aa000000010000000a00000058e05aba22fd2b9defd00294f46a051b74"
                    + "5c8d0206eb7d037ea85083b095f3fbe9f64b40dc042a1ada732ffc6d6eaf8a592"
                    + "cd2b6acbc18d0f4fba2262af13a4ce471548be659b5d0da181687da33b326d566309eac0000aa55";
            assertArrayEquals(HexFormat.of().parseHex(expected),
                    peer.getInputStream().readNBytes(expected.length() / 2));
        }
    }

    @Test
    void reconnectsForSecondLegacyQueryAfterReset() throws Exception {
        verifyReconnect(1);
    }

    @Test
    void fallsBackAfterRepeatedResetsAndRemembersExplicitPoints() throws Exception {
        verifyReconnect(2);
    }

    private void verifyReconnect(int resets) throws Exception {
        var points = InkbirdProfile.COOKER.dataPoints();
        try (var server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(2000);
            var peerTask = executor.submit(() -> {
                for (int i = 0; i < resets; i++) {
                    try (var peer = server.accept()) {
                        peer.setSoTimeout(2000);
                        request(new DataInputStream(peer.getInputStream()), 10);
                        peer.setSoLinger(true, 0);
                    }
                }
                try (var peer = server.accept()) {
                    peer.setSoTimeout(2000);
                    var input = new DataInputStream(peer.getInputStream());
                    int cmd = resets == 2 ? 13 : 10;
                    for (int i = 0; i < 2; i++) {
                        var json = request(input, cmd);
                        if (cmd == 13) {
                            var dps = json.getAsJsonObject("dps");
                            assertEquals(points, dps.keySet());
                            points.forEach(id -> assertTrue(dps.get(id).isJsonNull()));
                        }
                        peer.getOutputStream().write(reply(2300 + i, cmd, "{\"dps\":{\"104\":207}}"));
                    }
                }
                return true;
            });
            try (var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                    "example", KEY)) {
                assertEquals(207, client.query(points).get("104").getAsInt());
                assertEquals(resets == 2, client.usesExplicitPoints());
                assertEquals(207, client.query(points).get("104").getAsInt());
            }
            assertTrue(peerTask.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void reportsDeviceReturnCodeAfterCheckingFrame() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                        "example", KEY);
                var peer = server.accept()) {
            var bytes = reply(700, 10, "query unavailable");
            ByteBuffer.wrap(bytes).putInt(16, 5);
            var crc = new CRC32();
            crc.update(bytes, 0, bytes.length - 8);
            ByteBuffer.wrap(bytes).putInt(bytes.length - 8, (int) crc.getValue());
            peer.getOutputStream().write(bytes);
            var failure = assertThrows(IOException.class, () -> client.query(InkbirdProfile.COOKER.dataPoints()));
            assertEquals("Device returned code 5 for command 10", failure.getMessage());
        }
    }

    private static byte[] reply(int sequence, int command, String text) throws Exception {
        var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.US_ASCII), "AES"));
        byte[] encrypted = text.isEmpty() ? new byte[0] : cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] payload = command == 8
                ? ByteBuffer.allocate(15 + encrypted.length).put("3.3".getBytes(StandardCharsets.US_ASCII)).position(15)
                        .put(encrypted).array()
                : encrypted;
        var frame = ByteBuffer.allocate(28 + payload.length);
        frame.putInt(0x55aa).putInt(sequence).putInt(command).putInt(payload.length + 12).putInt(0).put(payload);
        var crc = new CRC32();
        crc.update(frame.array(), 0, frame.position());
        return frame.putInt((int) crc.getValue()).putInt(0xaa55).array();
    }

    private static JsonObject request(DataInputStream input, int command) throws Exception {
        assertEquals(0x55aa, input.readInt());
        input.readInt();
        assertEquals(command, input.readInt());
        byte[] body = input.readNBytes(input.readInt());
        int offset = command == 7 || command == 13 ? 15 : 0;
        if (offset != 0) {
            assertEquals("3.3", new String(body, 0, 3, StandardCharsets.US_ASCII));
            assertArrayEquals(new byte[12], Arrays.copyOfRange(body, 3, 15));
        }
        var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.US_ASCII), "AES"));
        byte[] plaintext = cipher.doFinal(Arrays.copyOfRange(body, offset, body.length - 8));
        return JsonParser.parseString(new String(plaintext, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
