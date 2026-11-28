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

/**
 * The {@link InkbirdConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
public class InkbirdConfiguration {
    public String hostname = "";
    public String deviceId = "";
    public String localKey = "";
    public String protocolVersion = "3.3";
    public int refreshInterval = 30;
}
