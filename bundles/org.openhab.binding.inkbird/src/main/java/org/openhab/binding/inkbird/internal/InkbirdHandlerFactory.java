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

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;

/**
 * The {@link InkbirdHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Andreas Rothstein - Initial contribution
 */
@NonNullByDefault
@Component(service = ThingHandlerFactory.class)
public class InkbirdHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(ITC_308, ISV_100);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (ITC_308.equals(thingTypeUID)) {
            return new InkbirdHandler(thing, InkbirdProfile.THERMOSTAT);
        } else if (ISV_100.equals(thingTypeUID)) {
            return new InkbirdHandler(thing, InkbirdProfile.COOKER);
        }

        return null;
    }
}
