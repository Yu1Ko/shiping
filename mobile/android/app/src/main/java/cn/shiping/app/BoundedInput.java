package cn.shiping.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class BoundedInput {
    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(8192, limit)];
        while (output.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count < 0) break;
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
