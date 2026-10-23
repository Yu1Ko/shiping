package cn.shiping.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private, atomic encrypted storage. Keys never leave Android Keystore. */
final class SecureStore {
    private final AtomicFile file;
    private static final String ALIAS = "shiping-v1";
    private static final int MAX_BYTES = 24 * 1024 * 1024;
    private static final Object STORE_LOCK = new Object();

    SecureStore(Context context) {
        this(context, "state.enc");
    }

    SecureStore(Context context, String name) {
        file = new AtomicFile(new File(context.getFilesDir(), name));
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    String read() throws Exception { synchronized (STORE_LOCK) { return readLocked(); } }

    private String readLocked() throws Exception {
        if (!file.getBaseFile().exists()) return "null";
        byte[] data = file.readFully();
        if (data.length < 29) throw new IllegalStateException("本地数据损坏，无法解密");
        ByteBuffer bytes = ByteBuffer.wrap(data);
        byte[] iv = new byte[12];
        bytes.get(iv);
        byte[] encrypted = new byte[bytes.remaining()];
        bytes.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    void write(String value) throws Exception { synchronized (STORE_LOCK) { writeLocked(value); } }

    private void writeLocked(String value) throws Exception {
        byte[] plain = value.getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_BYTES) throw new IllegalArgumentException("历史图片已超过 24 MB，请删除部分历史记录后重试");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(cipher.getIV());
            stream.write(cipher.doFinal(plain));
            file.finishWrite(stream);
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            throw e;
        }
    }
}
