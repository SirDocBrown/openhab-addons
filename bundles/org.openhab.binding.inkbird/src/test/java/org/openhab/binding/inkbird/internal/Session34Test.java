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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises the 3.4 challenge exchange and a signed query against an independent loopback peer.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@Timeout(10)
@NonNullByDefault
class Session34Test {
    private static final byte[] KEY = "1234567890abcdef".getBytes(US_ASCII);

    @Test
    void negotiatesAndReadsWrappedStatus() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(3000);
            var task = executor.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(3000);
                    emulate(peer);
                }
                return true;
            });
            try (var client = new LocalConnection(server.getInetAddress().getHostAddress(), server.getLocalPort(),
                    "example", new String(KEY, US_ASCII), true,
                    java.time.Clock.fixed(java.time.Instant.ofEpochSecond(1700000000), java.time.ZoneOffset.UTC),
                    "0123456789abcdef".getBytes(US_ASCII))) {
                var status = client.query(InkbirdProfile.THERMOSTAT.dataPoints());
                assertTrue(InkbirdProfile.THERMOSTAT.matches(status));
                assertEquals(215, status.get("104").getAsInt());
                assertEquals(980, status.get("106").getAsInt());
                var command = new com.google.gson.JsonObject();
                command.addProperty("106", 220);
                client.write(command);
            }
            assertTrue(task.get(4, TimeUnit.SECONDS));
        }
    }

    private static void emulate(Socket peer) throws Exception {
        var in = new DataInputStream(peer.getInputStream());
        byte[] clientNonce = read(in, 3, KEY);
        byte[] peerNonce = "independentNonce".getBytes(US_ASCII);
        byte[] challenge = ByteBuffer.allocate(48).put(peerNonce).put(mac(KEY, clientNonce)).array();
        reply(peer, 43649, 4, KEY, challenge);
        assertArrayEquals(mac(KEY, peerNonce), read(in, 5, KEY));
        for (int i = 0; i < 16; i++) {
            peerNonce[i] ^= clientNonce[i];
        }
        var derive = Cipher.getInstance("AES/ECB/NoPadding");
        derive.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"));
        byte[] session = derive.doFinal(peerNonce);
        // TinyTuya 1.21.1: import tinytuya as t
        // d = t.Device("example", "127.0.0.1", "1234567890abcdef", version=3.4)
        // d.local_nonce, d.remote_nonce = b"0123456789abcdef", b"independentNonce"
        // d._negotiate_session_key_generate_finalize(); d.seqno = 3
        // print(d._encode_message(d.generate_payload(t.DP_QUERY)).hex())
        String expected = "000055aa000000030000001000000034546e77f8c0437ed0dac7c6285de1b665"
                + "555c23ca5793c619f820c257cc2018c36eb457c8c2709e97b37ecc712cd8b6840000aa55";
        assertArrayEquals(java.util.HexFormat.of().parseHex(expected), in.readNBytes(expected.length() / 2));
        reply(peer, 43650, 16, session,
                "{\"data\":{\"dps\":{\"101\":\"C\",\"104\":215,\"106\":980,\"111\":false,\"112\":false,\"113\":false,\"115\":3,\"116\":719}}}"
                        .getBytes(US_ASCII));
        byte[] status = "{\"data\":{\"dps\":{\"104\":197}}}".getBytes(US_ASCII);
        reply(peer, 43651, 8, session,
                ByteBuffer.allocate(15 + status.length).put("3.4".getBytes(US_ASCII)).position(15).put(status).array());
        byte[] change = read(in, 13, session);
        assertEquals("3.4", new String(change, 0, 3, US_ASCII));
        var json = com.google.gson.JsonParser.parseString(new String(change, 15, change.length - 15, US_ASCII))
                .getAsJsonObject();
        assertEquals(220, json.getAsJsonObject("data").getAsJsonObject("dps").get("106").getAsInt());
        reply(peer, 4, 13, session, new byte[0]);
    }

    private static byte[] read(DataInputStream in, int command, byte[] key) throws Exception {
        byte[] header = in.readNBytes(16);
        var fields = ByteBuffer.wrap(header);
        assertEquals(0x55aa, fields.getInt(0));
        assertEquals(command, fields.getInt(8));
        byte[] data = in.readNBytes(fields.getInt(12) - 36);
        byte[] signed = ByteBuffer.allocate(16 + data.length).put(header).put(data).array();
        assertArrayEquals(mac(key, signed), in.readNBytes(32));
        assertEquals(0xaa55, in.readInt());
        return cipher(key, Cipher.DECRYPT_MODE, data);
    }

    private static void reply(Socket peer, int seq, int command, byte[] key, byte[] plain) throws Exception {
        byte[] data = plain.length == 0 ? plain : cipher(key, Cipher.ENCRYPT_MODE, plain);
        var out = ByteBuffer.allocate(56 + data.length);
        out.putInt(0x55aa).putInt(seq).putInt(command).putInt(40 + data.length).putInt(0).put(data);
        out.put(mac(key, Arrays.copyOf(out.array(), out.position()))).putInt(0xaa55);
        peer.getOutputStream().write(out.array());
    }

    private static byte[] cipher(byte[] key, int mode, byte[] input) throws Exception {
        var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(input);
    }

    private static byte[] mac(byte[] key, byte[] data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
