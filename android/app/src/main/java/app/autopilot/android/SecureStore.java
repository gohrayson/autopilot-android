package app.autopilot.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

/** AES-GCM, AndroidKeyStore, app-private no-backup directory; no plaintext fallback. */
public class SecureStore {
    private static final String ALIAS = "app.autopilot.android.accounts.v1";
    private static final int MAX_BYTES = 12 * 1024 * 1024;
    private final File directory;

    public SecureStore(Context context) {
        directory = new File(context.getNoBackupFilesDir(), "accounts");
    }

    // A protected constructor permits fixture-only in-memory storage without AndroidKeyStore.
    protected SecureStore() { directory = null; }

    private AtomicFile file(String name) throws Exception {
        if (directory == null || name == null || !name.matches("[a-z]{1,24}")) throw new Exception();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception();
        return new AtomicFile(new File(directory, name + ".enc"));
    }

    private SecretKey key() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
        keys.load(null);
        if (keys.containsAlias(ALIAS)) return (SecretKey) keys.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    public synchronized JSONObject load(String name) throws ProviderException {
        try {
            AtomicFile target = file(name);
            if (!target.getBaseFile().exists() && !new File(target.getBaseFile() + ".bak").exists()) return null;
            byte[] bytes;
            try (FileInputStream input = target.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > MAX_BYTES) throw new Exception();
                    output.write(buffer, 0, n);
                }
                bytes = output.toByteArray();
            }
            if (bytes.length < 30 || bytes[0] != 1 || bytes[1] != 12) throw new Exception();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 2, 12));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new JSONObject(new String(cipher.doFinal(bytes, 14, bytes.length - 14), StandardCharsets.UTF_8));
        } catch (Exception ignored) { throw new ProviderException("CREDENTIAL_STORE", "restore"); }
    }

    public synchronized void save(String name, JSONObject value) throws ProviderException {
        AtomicFile target = null; FileOutputStream output = null;
        try {
            target = file(name);
            if (value == null) {
                target.delete();
                if (target.getBaseFile().exists() || new File(target.getBaseFile() + ".bak").exists() || new File(target.getBaseFile() + ".new").exists()) throw new Exception();
                return;
            }
            byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_BYTES - 30) throw new Exception();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new Exception();
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plain);
            output = target.startWrite();
            output.write(new byte[] { 1, 12 }); output.write(iv); output.write(ciphertext);
            target.finishWrite(output); output = null;
        } catch (Exception ignored) {
            if (target != null && output != null) target.failWrite(output);
            throw new ProviderException("CREDENTIAL_STORE", "session-save");
        }
    }
}
