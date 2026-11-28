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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Bounded request/response session for the Tuya LAN 3.3 and 3.4 protocols.
 * Wire format reference: https://github.com/jasonacox/tinytuya/blob/master/PROTOCOL.md
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
final class LocalConnection implements AutoCloseable {
    private final Logger logger = LoggerFactory.getLogger(LocalConnection.class);
    private static final int STATUS = 8;
    private static final int PREFIX = 0x55AA;
    private static final int SUFFIX = 0xAA55;
    private static final int QUERY = 10;
    private static final int CONTROL = 7;
    private static final int TIMEOUT = 3000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final long exchangeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    private final Clock clock;
    private final InetSocketAddress address;
    private final SecretKeySpec deviceKey;
    private Socket socket;
    private boolean receivedFrame;
    private int receivedCommand;
    private SecretKeySpec key;
    private final boolean modern;
    private final String deviceId;
    private int sequence;
    private boolean explicitPoints;

    LocalConnection(String host, int port, String deviceId, String localKey)
            throws IOException, GeneralSecurityException {
        this(host, port, deviceId, localKey, false);
    }

    LocalConnection(String host, int port, String deviceId, String localKey, boolean modern)
            throws IOException, GeneralSecurityException {
        this(host, port, deviceId, localKey, modern, Clock.systemUTC(), modern ? RANDOM.generateSeed(16) : new byte[0]);
    }

    LocalConnection(String host, int port, String deviceId, String localKey, boolean modern, Clock clock, byte[] nonce)
            throws IOException, GeneralSecurityException {
        this.clock = clock;
        this.modern = modern;
        this.deviceId = deviceId;
        key = new SecretKeySpec(localKey.getBytes(StandardCharsets.UTF_8), "AES");
        deviceKey = key;
        address = new InetSocketAddress(host, port);
        socket = new Socket();
        logger.trace("Connecting to {} attempt 1/3", address);
        connect(nonce);
    }

    private void connect(byte[] nonce) throws IOException, GeneralSecurityException {
        sequence = 0;
        key = deviceKey;
        try {
            socket.setTcpNoDelay(true);
            socket.connect(address, Math.min(TIMEOUT, remainingMillis(exchangeDeadline)));
            socket.setSoTimeout(TIMEOUT);
            if (modern) {
                negotiate(nonce.clone());
            }
        } catch (java.io.InterruptedIOException e) {
            socket.close();
            throw e;
        } catch (IOException | GeneralSecurityException e) {
            logger.trace("Connect to {} failed: {}", address, e.getClass().getSimpleName());
            socket.close();
            throw e;
        }
    }

    JsonObject query(Set<String> points) throws IOException, GeneralSecurityException {
        return query(points, explicitPoints);
    }

    boolean usesExplicitPoints() {
        return explicitPoints;
    }

    JsonObject query(Set<String> points, boolean preferExplicit) throws IOException, GeneralSecurityException {
        boolean explicit = !modern && (preferExplicit || explicitPoints || deviceId.length() == 22);
        for (int attempt = 1;; attempt++) {
            logger.trace("Query {} attempt {}/3", address, attempt);
            receivedFrame = false;
            try {
                if (attempt > 1) {
                    try {
                        Thread.sleep(Math.min(100, remainingMillis(exchangeDeadline)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted before reconnect", e);
                    }
                    socket = new Socket();
                    connect(modern ? RANDOM.generateSeed(16) : new byte[0]);
                    receivedFrame = false;
                }
                long deadline = responseDeadline();
                if (explicit || (!modern && attempt == 3)) {
                    return queryPoints(points, deadline);
                }
                int command = modern ? 16 : QUERY;
                send(command, new JsonObject());
                try {
                    return readStatus(command, deadline, points);
                } catch (BadPaddingException | IllegalBlockSizeException | JsonParseException e) {
                    if (modern) {
                        throw e;
                    }
                    logger.trace("Retry legacy query with explicit data points");
                    return queryPoints(points, deadline);
                }
            } catch (SocketException | EOFException e) {
                logger.trace("Query {} attempt {}/3 failed: {}", address, attempt, e.getClass().getSimpleName());
                if (receivedFrame || attempt == 3) {
                    throw e;
                }
                close();
            }
        }
    }

    private JsonObject queryPoints(Set<String> points, long deadline) throws IOException, GeneralSecurityException {
        var requested = new JsonObject();
        points.stream().sorted(java.util.Comparator.comparingInt(Integer::parseInt))
                .forEach(id -> requested.add(id, JsonNull.INSTANCE));
        send(13, requested);
        var status = readStatus(13, deadline, points);
        explicitPoints = true;
        return status;
    }

    private JsonObject readStatus(int command, long deadline, Set<String> points)
            throws IOException, GeneralSecurityException {
        while (true) {
            var response = receive(command, deadline);
            if (response != null && response.has("dps") && response.get("dps").isJsonObject()) {
                var dps = response.getAsJsonObject("dps");
                if (receivedCommand != STATUS || dps.keySet().containsAll(points)) {
                    return dps;
                }
                logger.trace("Partial status push from {}; waiting for query reply", address);
            }
        }
    }

    void write(JsonObject changes) throws IOException, GeneralSecurityException {
        long deadline = responseDeadline();
        send(modern ? 13 : CONTROL, changes);
        while (true) {
            var response = receive(modern ? 13 : CONTROL, deadline);
            if (response != null && (receivedCommand != STATUS || (response.get("dps") instanceof JsonObject dps
                    && dps.entrySet().containsAll(changes.entrySet())))) {
                return;
            }
        }
    }

    private void send(int command, JsonObject changes) throws IOException, GeneralSecurityException {
        var request = new JsonObject();
        if (modern) {
            if (command == 13) {
                request.addProperty("protocol", 5);
                request.addProperty("t", clock.instant().getEpochSecond());
                var data = new JsonObject();
                data.add("dps", changes);
                request.add("data", data);
            }
        } else {
            if (command == QUERY) {
                request.addProperty("gwId", deviceId);
            }
            request.addProperty("devId", deviceId);
            request.addProperty("uid", deviceId);
            request.addProperty("t", Long.toString(clock.instant().getEpochSecond()));
            if (command != QUERY) {
                request.add("dps", changes);
            }
        }
        logger.trace("TX JSON cmd={} payload={}", command, request);
        sendFrame(command, request.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void negotiate(byte[] nonce) throws IOException, GeneralSecurityException {
        sendFrame(3, nonce);
        byte[] reply = readFrame(4, responseDeadline());
        if (reply == null || reply.length != 48
                || !MessageDigest.isEqual(hmac(nonce), Arrays.copyOfRange(reply, 16, 48))) {
            throw new IOException("Invalid session challenge");
        }
        byte[] remote = Arrays.copyOf(reply, 16);
        sendFrame(5, hmac(remote));
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] ^= remote[i];
        }
        Cipher derive = Cipher.getInstance("AES/ECB/NoPadding");
        derive.init(Cipher.ENCRYPT_MODE, key);
        key = new SecretKeySpec(derive.doFinal(nonce), "AES");
    }

    private byte[] hmac(byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
        return mac.doFinal(data);
    }

    private void sendFrame(int command, byte[] payload) throws IOException, GeneralSecurityException {
        byte[] plain = payload;
        if (modern && command == 13) {
            plain = ByteBuffer.allocate(15 + payload.length).put((byte) '3').put((byte) '.').put((byte) '4')
                    .position(15).put(payload).array();
        }
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, plain);
        boolean clearVersion = !modern && (command == CONTROL || command == 13);
        int length = encrypted.length + (clearVersion ? 15 : 0) + (modern ? 36 : 8);
        var frame = ByteBuffer.allocate(16 + length);
        frame.putInt(PREFIX).putInt(++sequence).putInt(command).putInt(length);
        if (clearVersion) {
            frame.put(Arrays.copyOf("3.3".getBytes(StandardCharsets.US_ASCII), 15));
        }
        frame.put(encrypted);
        if (modern) {
            frame.put(hmac(Arrays.copyOf(frame.array(), frame.position())));
        } else {
            var crc = new CRC32();
            crc.update(frame.array(), 0, frame.position());
            frame.putInt((int) crc.getValue());
        }
        frame.putInt(SUFFIX);
        if (logger.isTraceEnabled()) {
            logger.trace("TX frame seq={} cmd={} hex={}", sequence, command, HexFormat.of().formatHex(frame.array()));
        }
        remainingMillis(exchangeDeadline);
        socket.getOutputStream().write(frame.array());
    }

    private @Nullable JsonObject receive(int expectedCommand, long deadline)
            throws IOException, GeneralSecurityException {
        byte[] plain = readFrame(expectedCommand, deadline);
        if (plain == null) {
            return null;
        }
        if (plain.length == 0) {
            return new JsonObject();
        }
        int start = modern && plain.length >= 15 && plain[0] == '3' && plain[1] == '.' && plain[2] == '4' ? 15 : 0;
        String json = new String(plain, start, plain.length - start, StandardCharsets.UTF_8);
        logger.trace("RX plaintext {}", json);
        if (!modern && expectedCommand == QUERY && json.contains("json obj data unvalid")) {
            throw new JsonParseException("Device rejected the legacy query format");
        }
        var parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new JsonParseException("Expected a device status object");
        }
        var result = parsed.getAsJsonObject();
        return result.get("data") instanceof JsonObject nested ? nested : result;
    }

    private byte @Nullable [] readFrame(int expectedCommand, long deadline)
            throws IOException, GeneralSecurityException {
        var input = socket.getInputStream();
        byte[] header = new byte[16];
        readFully(input, header, deadline);
        var fields = ByteBuffer.wrap(header);
        int length = fields.getInt(12);
        int command = fields.getInt(8);
        int receivedSequence = fields.getInt(4);
        logger.trace("RX header prefix={} seq={} cmd={} length={}", Integer.toHexString(fields.getInt(0)),
                receivedSequence, command, length);
        if (fields.getInt(0) != PREFIX || length < (modern ? 40 : 12) || length > 65536) {
            throw new IOException("Invalid device frame");
        }
        byte[] body = new byte[length];
        readFully(input, body, deadline);
        var trailer = ByteBuffer.wrap(body);
        logger.trace("RX seq={} cmd={} returnCode={}", receivedSequence, command, trailer.getInt(0));
        int end = length - (modern ? 36 : 8);
        boolean valid;
        if (modern) {
            byte[] signed = ByteBuffer.allocate(16 + end).put(header).put(body, 0, end).array();
            valid = MessageDigest.isEqual(hmac(signed), Arrays.copyOfRange(body, end, end + 32));
        } else {
            var crc = new CRC32();
            crc.update(header);
            crc.update(body, 0, end);
            valid = (int) crc.getValue() == trailer.getInt(end);
        }
        if (!valid || trailer.getInt(length - 4) != SUFFIX) {
            throw new IOException("Invalid device response");
        }
        receivedFrame = true;
        receivedCommand = command;
        if (command != expectedCommand && !(command == STATUS && expectedCommand != 4)) {
            logger.trace("Discard RX: unexpected command {}, expected {}", command, expectedCommand);
            return null;
        }
        int offset = !modern && end >= 19 && body[4] == '3' && body[5] == '.' && body[6] == '3' ? 19 : 4;
        int returnCode = trailer.getInt(0);
        if (returnCode != 0) {
            String detail = "no payload";
            if (end > offset && expectedCommand != 4) {
                try {
                    byte[] error = crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(body, offset, end));
                    int start = modern && error.length >= 15 && error[0] == '3' && error[1] == '.' && error[2] == '4'
                            ? 15
                            : 0;
                    detail = new String(error, start, error.length - start, StandardCharsets.UTF_8);
                } catch (GeneralSecurityException e) {
                    detail = "undecodable payload";
                }
            }
            logger.debug("Device {} cmd={} returnCode={} detail={}", address, command, returnCode, detail);
            throw new IOException("Device returned code " + returnCode + " for command " + command);
        }
        if (end == 4 && command != expectedCommand) {
            logger.trace("Discard RX: empty acknowledgement for unrelated command {}", command);
            return null;
        }
        if (end == 4) {
            logger.trace("RX empty acknowledgement");
            return new byte[0];
        }
        return crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(body, offset, end));
    }

    private long responseDeadline() {
        return Math.min(exchangeDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    }

    private static int remainingMillis(long deadline) throws SocketTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Device response deadline exceeded");
        }
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private void readFully(InputStream in, byte[] data, long deadline) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            socket.setSoTimeout(remainingMillis(deadline));
            int count = in.read(data, offset, data.length - offset);
            if (count < 0) {
                throw new EOFException("Device closed connection during response");
            }
            offset += count;
        }
    }

    private byte[] crypt(int mode, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, key);
        return cipher.doFinal(input);
    }

    @Override
    public void close() throws IOException {
        if (socket.isClosed()) {
            return;
        }
        try {
            socket.shutdownOutput();
            // Drain queued pushes after FIN, bounded even if the peer keeps transmitting.
            long deadline = Math.min(exchangeDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100));
            byte[] buffer = new byte[2048];
            InputStream input = socket.getInputStream();
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                if (input.read(buffer) < 0) {
                    break;
                }
            }
        } catch (java.io.InterruptedIOException e) {
            // A bounded drain may time out while the peer is still connected.
        } catch (IOException e) {
            logger.trace("Closing {}: {}", address, e.getClass().getSimpleName());
        } finally {
            socket.close();
        }
    }
}
