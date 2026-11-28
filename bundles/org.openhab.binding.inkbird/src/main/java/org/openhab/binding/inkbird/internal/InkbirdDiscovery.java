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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Listens for Tuya LAN advertisements during an Inbox scan. See TinyTuya's
 * <a href="https://github.com/jasonacox/tinytuya/blob/master/tinytuya/core/udp_helper.py">UDP decoder</a>.
 * Product IDs come from the ITC-308 Tuya Local profile and the reported Inkbird sous vide hardware scan.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
@Component(service = DiscoveryService.class)
public class InkbirdDiscovery extends AbstractDiscoveryService {
    private final Logger logger = LoggerFactory.getLogger(InkbirdDiscovery.class);
    private volatile int generation;

    public InkbirdDiscovery() {
        super(Set.of(ITC_308, ISV_100), 25, false);
    }

    @Override
    protected synchronized void startScan() {
        int scan = ++generation;
        scheduler.execute(() -> listen(scan));
    }

    @Override
    protected synchronized void stopScan() {
        generation++;
        super.stopScan();
    }

    private void listen(int scan) {
        try (var selector = Selector.open();
                var clear = DatagramChannel.open(StandardProtocolFamily.INET);
                var encrypted = DatagramChannel.open(StandardProtocolFamily.INET)) {
            register(clear, selector, 6666);
            register(encrypted, selector, 6667);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(24);
            var buffer = ByteBuffer.allocate(4096);
            while (!Thread.currentThread().isInterrupted() && scan == generation && !selector.keys().isEmpty()
                    && System.nanoTime() < deadline) {
                selector.select(400);
                var keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    var key = keys.next();
                    keys.remove();
                    buffer.clear();
                    var channel = (DatagramChannel) key.channel();
                    if (channel.receive(buffer) instanceof InetSocketAddress sender) {
                        try {
                            var result = decode(Arrays.copyOf(buffer.array(), buffer.position()),
                                    sender.getAddress().getHostAddress());
                            if (result != null && scan == generation) {
                                thingDiscovered(result);
                            }
                        } catch (GeneralSecurityException | RuntimeException e) {
                            logger.trace("Ignored malformed Inkbird discovery packet");
                        }
                    }
                }
            }
        } catch (java.io.InterruptedIOException e) {
            return;
        } catch (IOException e) {
            logger.debug("Inkbird discovery socket error: {}", e.getMessage());
        }
    }

    private void register(DatagramChannel channel, Selector selector, int port) {
        try {
            bind(channel, port);
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ);
        } catch (java.io.InterruptedIOException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.debug("Cannot listen for Inkbird discovery on UDP port {}: {}", port, e.getMessage());
        }
    }

    static void bind(DatagramChannel channel, int port) throws IOException {
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        if (channel.supportedOptions().contains(StandardSocketOptions.SO_REUSEPORT)) {
            channel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
        }
        channel.bind(new InetSocketAddress(port));
    }

    static @Nullable DiscoveryResult decode(byte[] packet, String host) throws GeneralSecurityException {
        if (packet.length < 28 || packet.length > 4096) {
            return null;
        }
        var frame = ByteBuffer.wrap(packet);
        int end = packet.length - 8;
        var crc = new CRC32();
        crc.update(packet, 0, end);
        if (frame.getInt(0) != 0x55aa || frame.getInt(12) != packet.length - 16
                || frame.getInt(end) != (int) crc.getValue() || frame.getInt(end + 4) != 0xaa55) {
            return null;
        }
        int start = frame.getInt(16) == 0 ? 20 : 16;
        byte[] payload = Arrays.copyOfRange(packet, start, end);
        if (payload.length == 0) {
            return null;
        }
        if (payload[0] != '{' || payload[payload.length - 1] != '}') {
            var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            byte[] key = MessageDigest.getInstance("MD5")
                    .digest("yGAdlopoPVldABfn".getBytes(StandardCharsets.US_ASCII));
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            payload = cipher.doFinal(payload);
        }
        if (!(JsonParser.parseString(new String(payload, StandardCharsets.UTF_8).trim()) instanceof JsonObject data)) {
            return null;
        }
        String id = field(data, "gwId");
        String version = field(data, "version");
        String product = field(data, "productKey");
        @Nullable
        ThingTypeUID model = switch (product.isEmpty() ? field(data, "product_id") : product) {
            case "rt9nqdxeiqo7dnz9", "B0eP8qYAdpUo4yR9" -> ITC_308;
            case "iPiqkMPG2lOUtpqB" -> ISV_100;
            default -> null;
        };
        if (model == null || !id.matches("[A-Za-z0-9]{1,64}") || !Set.of("3.3", "3.4").contains(version)) {
            return null;
        }
        return DiscoveryResultBuilder.create(new ThingUID(model, id))
                .withLabel(model.equals(ITC_308) ? "Inkbird ITC-308-WIFI" : "Inkbird ISV-100W")
                .withRepresentationProperty("deviceId")
                .withProperties(Map.of("deviceId", id, "hostname", host, "protocolVersion", version)).build();
    }

    private static String field(JsonObject data, String name) {
        var value = data.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
