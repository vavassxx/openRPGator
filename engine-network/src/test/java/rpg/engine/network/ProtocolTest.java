package rpg.engine.network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    private static Packet roundTrip(Packet packet) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.write(bytes, packet);
        return Protocol.read(new ByteArrayInputStream(bytes.toByteArray()));
    }

    @Test
    void roundTripsScriptCmdAndMap() throws IOException {
        Script script = new Script("sandbox-demo", "ui.notify('hello'); ui.send(9002, 'sword')");
        Script scriptRt = (Script) roundTrip(script);
        assertEquals(script.name(), scriptRt.name());
        assertEquals(script.source(), scriptRt.source());

        Cmd cmd = new Cmd(9002, "sword");
        assertEquals(new Cmd(9002, "sword"), roundTrip(cmd));
        assertEquals(new Cmd(7, ""), roundTrip(new Cmd(7, null)));

        MapPacket map = new MapPacket(3, 2, new int[]{0, 1, 2, 3, 4, 5});
        MapPacket mapRt = (MapPacket) roundTrip(map);
        assertEquals(3, mapRt.width());
        assertEquals(2, mapRt.height());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5}, mapRt.tiles());
    }

    @Test
    void rejectsBrokenMapPackets() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(13); // type byte + 3 ints
            out.writeByte(14);
            out.writeInt(3); out.writeInt(2); out.writeInt(7); // 3x2 must have 6 tiles
            out.flush();
            Protocol.read(new ByteArrayInputStream(bytes.toByteArray()));
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("map packet"), expected.getMessage());
        }
    }

    @Test
    void roundTripsAllPackets() throws IOException {
        assertEquals(new Hello("тест"), roundTrip(new Hello("тест")));
        assertEquals(new Welcome(42), roundTrip(new Welcome(42)));
        assertEquals(new Input(1.25, -2.5, Input.PRIMARY | Input.INTERACT),
                roundTrip(new Input(1.25, -2.5, Input.PRIMARY | Input.INTERACT)));

        Snapshot snapshot = new Snapshot(List.of(
                new Snapshot.EntityState(1, 1.0, 2.0, 0.0, "guard", 1.0),
                new Snapshot.EntityState(2, -3.5, 4.25, 1.5, "player", 2.5)
        ));
        assertEquals(snapshot, roundTrip(snapshot));

        PakList pakList = new PakList(List.of(
                new PakList.PakSeq("basic.pak", 123456),
                new PakList.PakSeq("ui.pak", 1024)
        ));
        assertEquals(pakList, roundTrip(pakList));
        PakChunk chunk = new PakChunk("basic.pak", 65536, new byte[]{1, 2, 3});
        PakChunk chunkRt = (PakChunk) roundTrip(chunk);
        assertEquals(chunk.name(), chunkRt.name());
        assertEquals(chunk.offset(), chunkRt.offset());
        assertArrayEquals(chunk.data(), chunkRt.data());

        UiLayout dialog = UiLayout.dialog(7, "Тело диалога", List.of("А", "Б", "В"));
        UiLayout dialogRt = (UiLayout) roundTrip(dialog);
        assertEquals(dialog.kind(), dialogRt.kind());
        assertEquals(dialog.dialogId(), dialogRt.dialogId());
        assertEquals(dialog.json(), dialogRt.json());
        assertEquals("Тело диалога", dialogRt.bodyText());
        assertEquals(List.of("А", "Б", "В"), dialogRt.choiceTexts());

        UiLayout notify = UiLayout.notify("Тост");
        UiLayout notifyRt = (UiLayout) roundTrip(notify);
        assertEquals(UiLayout.KIND_NOTIFY, notifyRt.kind());
        assertEquals("Тост", notifyRt.bodyText());
        assertTrue(notifyRt.choiceTexts().isEmpty());

        // Host-driven widget surface round-trips the layout JSON + strings verbatim.
        UiLayout widgetLayout = UiLayout.layout(
                "[{\"type\":\"bar\",\"x\":0.01,\"y\":0.1,\"w\":0.16,\"h\":0.03,"
                        + "\"value\":95,\"max\":100},{\"type\":\"text\",\"x\":0.19,\"ref\":1}]",
                List.of("Здоровье", "HP 95/100"));
        UiLayout layoutRt = (UiLayout) roundTrip(widgetLayout);
        assertEquals(UiLayout.KIND_LAYOUT, layoutRt.kind());
        assertEquals(widgetLayout.json(), layoutRt.json());
        assertEquals(List.of("Здоровье", "HP 95/100"), layoutRt.strings());
        assertEquals(2, layoutRt.layoutWidgets().size());
        assertEquals("HP 95/100", layoutRt.strings().get(1));
    }

    @Test
    void framesMultiplePackets() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.write(bytes, new Hello("one"));
        Protocol.write(bytes, new Welcome(7));

        ByteArrayInputStream in = new ByteArrayInputStream(bytes.toByteArray());
        assertEquals(new Hello("one"), Protocol.read(in));
        assertEquals(new Welcome(7), Protocol.read(in));
    }
}
