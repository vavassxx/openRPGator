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
                new Snapshot.EntityState(1, 1.0, 2.0, 0.0),
                new Snapshot.EntityState(2, -3.5, 4.25, 1.5)
        ));
        assertEquals(snapshot, roundTrip(snapshot));
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
