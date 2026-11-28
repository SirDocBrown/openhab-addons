# Inkbird Binding

This binding communicates directly with Inkbird appliances using Tuya LAN protocols 3.3 and 3.4.
No cloud account is required during operation.

## Supported Things

| Thing type | Appliance |
| --- | --- |
| `itc-308-wifi` | Inkbird ITC-308-WIFI temperature controller |
| `isv-100w` | Inkbird ISV-100W sous vide cooker |

## Discovery

Start a scan for the Inkbird binding in the Inbox.
For 24 seconds the binding listens on UDP ports 6666 and 6667 for LAN broadcasts from supported products.
Accept the result to fill in the hostname, device ID and LAN protocol automatically.
After discovery, follow [Obtaining the Local Key](#obtaining-the-local-key) to supply the missing key.
The scanner uses the packet source address.
A container must be able to receive LAN broadcasts; a Docker host network on Linux is one option.
The official Tuya binding also listens on these ports.
Simultaneous discovery depends on the operating system and whether both listeners allow port sharing.
Configure each appliance in only one binding because many appliances accept a single local connection.
A manual Thing can also be created when broadcasts cannot reach openHAB.

## Obtaining the Local Key

Retrieve the key once for each appliance.
The binding then communicates with the appliance on your LAN.

### Smart Life QR Login

> QR login supports devices paired with Smart Life or Tuya Smart.
> If you use the INKBIRD app, switching the appliance to another app replaces its pairing and you should expect to lose control through INKBIRD.

1. If the appliance already appears in Smart Life or Tuya Smart, keep its existing pairing and continue with step 2.
   Otherwise, put the appliance in Wi-Fi pairing mode on your 2.4 GHz network.
   On the ITC-308-WIFI, hold the Wi-Fi/SET button until the Wi-Fi indicator flashes rapidly.
   On the ISV-100W, follow the Wi-Fi pairing instructions for your model.
   In the app, start an automatic scan or select the generic Wi-Fi device option (Other Wi-Fi).
1. Open **Me → Settings** (gear icon) **→ Account and Security** in the app and copy **User Code** from the bottom of the screen.
1. Run [tuya-local-key] on your home network using the Docker, CLI or Home Assistant instructions in its README.
   If you use the web interface, restrict access to your own computer or enable authentication as described in the tool documentation.
   Enter your User Code when prompted.
   If you already use Home Assistant, the cloud-assisted setup in [Tuya Local] is another option.
   Both tools are third-party community projects maintained independently of openHAB.
1. Have the app ready before requesting the QR code, as it can expire within about one to two minutes.
   In Smart Life, tap **+ → Scan**, scan the code and approve the login.
   If your devices are paired with Tuya Smart, scan and approve in that app.
   Request a fresh code if it expires.
1. Match the device ID shown by the tool to the device ID on the discovered Thing.
   Copy the corresponding Local Key into the Thing configuration and save it.
   For a manually created Thing, copy the device ID as well.
1. Treat each key like a password.
   The tool has access to your Tuya account session and can cache the device list and keys locally.
   After a one-time lookup, remove the temporary container or add-on and its cache directory or volume, including any CSV or JSON exports you created.

Each new pairing generates a new local key.
After pairing again, replace the old key on the Thing.

## Thing Configuration

Use the hostname or a reserved IP address, device ID and local key for the appliance.
See [Obtaining the Local Key](#obtaining-the-local-key) for setup instructions.
Keep the key private.
Close any other local client for that appliance before connecting.
Local connections use TCP port 6668.
Firmware using other LAN protocol versions requires separate support.

| Parameter | Description | Default |
| --- | --- | --- |
| `hostname` | IP address or hostname | Required |
| `deviceId` | Device identifier | Required |
| `localKey` | 16-byte local key | Required |
| `protocolVersion` | LAN protocol version (3.3 or 3.4) | Discovered or 3.3 |
| `refreshInterval` | Polling interval in seconds, minimum 5 | 30 |

## Channels

| Channel | Item type | ITC-308-WIFI | ISV-100W | Access |
| --- | --- | --- | --- | --- |
| `temperature` | `Number:Temperature` | Yes | Yes | Read |
| `target-temperature` | `Number:Temperature` | Yes | Yes | Read/write |
| `operating-state` | `String` | Yes | Yes | Read |
| `high-alarm` | `Switch` | Yes | — | Read |
| `low-alarm` | `Switch` | Yes | — | Read |
| `probe-fault` | `Switch` | Yes | — | Read |
| `running` | `Switch` | — | Yes | Read/write |
| `cooking-time` | `Number:Time` | — | Yes | Read/write |
| `remaining-time` | `Number:Time` | — | Yes | Read |
| `fault-code` | `Number` | — | Yes | Read |

Use temperature quantities with explicit units for commands.
The thermostat accepts target values in its active device unit.
The cooker profile uses tenths of a degree Celsius even when its front panel displays Fahrenheit.
The writable cooker target range is 25–96 °C, the intersection of the documented device and community protocol ranges.
Cooking time accepts whole minutes from 0 through 5999.
Starting or stopping a cycle is a separate command on `running`.

## Testing

If you installed an earlier test version, keep a private copy of each local key, then delete and recreate the Inkbird Things after updating (via an Inbox scan or manually) and enter their keys again.

## Full Example

### inkbird.things

```java
Thing inkbird:itc-308-wifi:controller [ hostname="192.0.2.1", deviceId="YOUR_DEVICE_ID", localKey="YOUR_LOCAL_KEY" ]
Thing inkbird:isv-100w:cooker [ hostname="192.0.2.2", deviceId="YOUR_DEVICE_ID", localKey="YOUR_LOCAL_KEY" ]
```

### inkbird.items

```java
Number:Temperature ProbeTemperature { channel="inkbird:itc-308-wifi:controller:temperature" }
Number:Temperature WaterTemperature { channel="inkbird:isv-100w:cooker:temperature" }
Number:Time CookingTime { channel="inkbird:isv-100w:cooker:cooking-time" }
Switch CookerRunning { channel="inkbird:isv-100w:cooker:running" }
```

## Protocol References

- [Tuya Local thermostat mapping](https://github.com/make-all/tuya-local/blob/main/custom_components/tuya_local/devices/inkbird_itc308_thermostat.yaml)
- [Tuya Local cooker mapping](https://github.com/make-all/tuya-local/blob/184bc3f398/custom_components/tuya_local/devices/inkbird_sousvide_cooker.yaml)
- [Community ISV-100W compatibility report](https://community.home-assistant.io/t/357977)
- [Cooker display unit investigation](https://github.com/make-all/tuya-local/issues/1416)
- [TinyTuya LAN protocol reference](https://github.com/jasonacox/tinytuya/blob/master/PROTOCOL.md)
- [Tasmota thermostat relay codes](https://tasmota.github.io/docs/TuyaMCU/#inkbird-itc-308-wifi)

## Key Retrieval Tools

- [tuya-local-key] provides a Docker container, CLI and Home Assistant add-on.
- [Tuya Local] provides cloud-assisted device setup for Home Assistant.

[tuya-local-key]: https://github.com/vineetchoudhary/tuya-local-key
[Tuya Local]: https://github.com/make-all/tuya-local
