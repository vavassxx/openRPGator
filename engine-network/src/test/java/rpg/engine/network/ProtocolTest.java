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
    void roundTripsAllPackets() throws IOException {
        assertEquals(new Hello("тест"), roundTrip(new Hello("тест")));
        assertEquals(new Welcome(42), roundTrip(new Welcome(42)));
        assertEquals(new Input(1.25, -2.5, Input.PRIMARY | Input.INTERACT),
                roundTrip(new Input(1.25, -2.5, Input.PRIMARY | Input.INTERACT)));

        Snapshot snapshot = new Snapshot(List.of(
                new Snapshot.EntityState(1, 1.0, 2.0, 0.0, 4),
                new Snapshot.EntityState(2, -3.5, 4.25, 1.5, -1)
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
