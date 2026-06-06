package dev.redish.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class RespSerializerTest {

    @Test
    void serializeErrorResponse() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RespSerializer.serialize(new ErrorResponse("ERR something went wrong"), out);
        assertEquals("-ERR something went wrong\r\n", out.toString(StandardCharsets.UTF_8));
    }
}
