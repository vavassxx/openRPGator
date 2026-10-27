
#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(".")
changes = []

def edit(path, replacements):
    p = ROOT / path
    s = p.read_text(encoding="utf-8")
    for old, new, label in replacements:
        count = s.count(old)
        if count != 1:
            raise SystemExit(
                f"{path}: anchor '{label}' expected once, found {count}; "
                "no changes written"
            )
        s = s.replace(old, new, 1)
    return p, s

packet_path = ROOT / "engine-network/src/main/java/rpg/engine/network/DataPacket.java"
if packet_path.exists():
    raise SystemExit(f"{packet_path} already exists")

new_packet = """package rpg.engine.network;

/** Host-to-client application data channel (protocol type 16). */
public record DataPacket(String channel, String payload) implements Packet {
    public DataPacket {
        if (channel == null || channel.isBlank() || channel.length() > 128)
            throw new IllegalArgumentException("invalid data channel");
        if (payload == null) payload = "";
        if (payload.length() > 60000)
            throw new IllegalArgumentException("data payload exceeds 60000 characters");
    }

    @Override public byte type() { return 16; }
}
"""

changes.append((packet_path, new_packet))

changes.append(edit(
    "engine-network/src/main/java/rpg/engine/network/Packet.java",
    [(
        "Cmd, MapPacket, Font {",
        "Cmd, MapPacket, Font, DataPacket {",
        "Packet permits"
    )]
))

changes.append(edit(
    "engine-network/src/main/java/rpg/engine/network/Protocol.java",
    [
        (
            " *   15 Font(name, bytes) — server-pushed TTF/OTF for custom UI\n",
            " *   15 Font(name, bytes) — server-pushed TTF/OTF for custom UI\n"
            " *   16 DataPacket(channel, payload) — application data\n",
            "protocol documentation"
        ),
        (
            """        } else if (packet instanceof Font p) {
            writeString(data, p.name());
            data.writeInt(p.data().length);
            data.write(p.data());
        } else {""",
            """        } else if (packet instanceof Font p) {
            writeString(data, p.name());
            data.writeInt(p.data().length);
            data.write(p.data());
        } else if (packet instanceof DataPacket p) {
            writeString(data, p.channel());
            writeString(data, p.payload());
        } else {""",
            "protocol writer"
        ),
        (
            "            case 15 -> { return new Font(readString(data), readBytes(data, MAX_FRAME_SIZE - 256)); }\n",
            "            case 15 -> { return new Font(readString(data), readBytes(data, MAX_FRAME_SIZE - 256)); }\n"
            "            case 16 -> { return new DataPacket(readString(data), readString(data)); }\n",
            "protocol reader"
        )
    ]
))

changes.append(edit(
    "engine-script/src/main/java/rpg/engine/script/UiSink.java",
    [(
        "    default void scriptTo(long playerEntityId, String name, String source) { }\n",
        "    default void scriptTo(long playerEntityId, String name, String source) { }\n\n"
        "    /** Sends bounded host-defined text data to one client. */\n"
        "    default void dataTo(long playerEntityId, String channel, String payload) { }\n",
        "UiSink data route"
    )]
))

changes.append(edit(
    "engine-script/src/main/java/rpg/engine/script/lua/LuaApi.java",
    [(
        """        engine.set("send_script", new ArgsLib() {
            public LuaValue callImpl(Varargs args) {
                long targetId = targetIdOf(args.arg(1));
                String source = args.arg(2).optjstring("");
                String name = args.narg() >= 3 ? args.arg(3).optjstring("client") : "client";
                if (uiSink == null || targetId == -1) return NONE;
                uiSink.scriptTo(targetId, name, source);
                return NONE;
            }
        });
""",
        """        engine.set("send_script", new ArgsLib() {
            public LuaValue callImpl(Varargs args) {
                long targetId = targetIdOf(args.arg(1));
                String source = args.arg(2).optjstring("");
                String name = args.narg() >= 3 ? args.arg(3).optjstring("client") : "client";
                if (uiSink == null || targetId == -1) return NONE;
                uiSink.scriptTo(targetId, name, source);
                return NONE;
            }
        });

        engine.set("send_data", new ArgsLib() {
            public LuaValue callImpl(Varargs args) {
                if (args.narg() < 3 || uiSink == null) return FALSE;
                long targetId = targetIdOf(args.arg(1));
                if (targetId == -1) return FALSE;
                String channel = args.arg(2).checkjstring();
                String payload = args.arg(3).optjstring("");
                if (channel.isBlank() || channel.length() > 128 || payload.length() > 60000)
                    throw new LuaError("engine.send_data: invalid channel or oversized payload");
                uiSink.dataTo(targetId, channel, payload);
                return TRUE;
            }
        });
""",
        "Lua engine.send_data"
    )]
))

changes.append(edit(
    "dedicated-server/src/main/java/rpg/engine/server/ServerHost.java",
    [(
        '            @Override public void scriptTo(long id,String name,String source) { Client c=clients.get(id); if(c!=null)c.send(new Script(name,source)); }\n',
        '            @Override public void scriptTo(long id,String name,String source) { Client c=clients.get(id); if(c!=null)c.send(new Script(name,source)); }\n'
        '            @Override public void dataTo(long id,String channel,String payload) {\n'
        '                Client c = clients.get(id);\n'
        '                if (c != null && channel != null && !channel.isBlank()\n'
        '                        && channel.length() <= 128 && payload != null && payload.length() <= 60000)\n'
        '                    c.send(new DataPacket(channel, payload));\n'
        '            }\n',
        "server data route"
    )]
))

changes.append(edit(
    "android-client/src/main/java/rpg/engine/android/ClientSession.java",
    [
        (
            "        void font(Font font); // a custom UI font finished downloading\n"
            "        void status(String s);",
            "        void font(Font font); // a custom UI font finished downloading\n"
            "        void data(DataPacket packet);\n"
            "        void status(String s);",
            "client listener"
        ),
        (
            "                    else if (q instanceof Script sc) listener.script(sc);\n"
            "                    // legacy push-UI packets",
            "                    else if (q instanceof Script sc) listener.script(sc);\n"
            "                    else if (q instanceof DataPacket dp) listener.data(dp);\n"
            "                    // legacy push-UI packets",
            "client data receive"
        )
    ]
))

changes.append(edit(
    "android-client/src/main/java/rpg/engine/android/MainActivity.java",
    [
        (
            '        Button shareLog = new Button(this);\n'
            '        shareLog.setText("Share log");\n'
            '        shareLog.setOnClickListener(v -> shareLog());\n'
            '        root.addView(shareLog, new LinearLayout.LayoutParams(-1, dp(48)));\n\n'
            '        // Storage info',
            '        Button shareLog = new Button(this);\n'
            '        shareLog.setText("Share log");\n'
            '        shareLog.setOnClickListener(v -> shareLog());\n'
            '        root.addView(shareLog, new LinearLayout.LayoutParams(-1, dp(48)));\n\n'
            '        CheckBox receiveData = new CheckBox(this);\n'
            '        receiveData.setText("Accept host application data");\n'
            '        receiveData.setTextColor(Color.WHITE);\n'
            '        receiveData.setChecked(prefs.getBoolean("accept_host_data", false));\n'
            '        receiveData.setOnCheckedChangeListener((button, checked) ->\n'
            '                prefs.edit().putBoolean("accept_host_data", checked).apply());\n'
            '        root.addView(receiveData, new LinearLayout.LayoutParams(-1, dp(48)));\n\n'
            '        TextView dataHint = new TextView(this);\n'
            '        dataHint.setText("Allows game scripts to deliver custom text payloads through named data channels.");\n'
            '        dataHint.setTextColor(Color.rgb(150, 155, 166));\n'
            '        dataHint.setTextSize(12);\n'
            '        root.addView(dataHint, new LinearLayout.LayoutParams(-1, -2));\n\n'
            '        // Storage info',
            "Android data toggle"
        ),
        (
            "    @Override public void font(Font font) {",
            '    @Override public void data(DataPacket packet) {\n'
            '        if (!prefs.getBoolean("accept_host_data", false)) return;\n'
            '        runOnUiThread(() -> {\n'
            '            if (isFinishing() || isDestroyed()) return;\n'
            '            logger.info("Host data [" + packet.channel() + "]: " + packet.payload());\n'
            '            Toast.makeText(this, "[" + packet.channel() + "] " + packet.payload(),\n'
            '                    Toast.LENGTH_LONG).show();\n'
            '        });\n'
            '    }\n\n'
            '    @Override public void font(Font font) {',
            "Android data handler"
        )
    ]
))

# Validate every anchor before writing anything.
for path, content in changes:
    if content is None:
        raise SystemExit(f"Internal error: missing content for {path}")

for path, content in changes:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")

print(f"Applied {len(changes)} file changes.")
print("Lua API: engine.send_data(player, channel, payload)")
print("Android opt-in: Settings -> Accept host application data")

