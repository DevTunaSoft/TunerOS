package sec02qa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import android.security.keystore.KeyGenParameterSpec;

/**
 * SEC-02 QA-only in-memory "AndroidKeyStore" provider. It performs real AES-GCM
 * through the JDK while replacing only the Android-specific key storage.
 */
public final class FakeAndroidKeyStoreProvider extends java.security.Provider {
    public static volatile boolean failGenerateKey = false;

    public FakeAndroidKeyStoreProvider() {
        super("AndroidKeyStore", 1.0, "SEC-02 QA deterministic fake");
        put("KeyGenerator.AES", FakeKeyGenerator.class.getName());
        put("KeyStore.AndroidKeyStore", FakeKeyStore.class.getName());
    }

    public static void reset() {
        failGenerateKey = false;
        FakeKeyStore.keys.clear();
    }

    public static final class FakeKeyGenerator extends KeyGeneratorSpi {
        @Override
        protected void engineInit(SecureRandom random) {
        }

        @Override
        protected void engineInit(AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidAlgorithmParameterException {
            if (params != null && !(params instanceof KeyGenParameterSpec)) {
                throw new InvalidAlgorithmParameterException("Unexpected spec: " + params.getClass().getName());
            }
        }

        @Override
        protected void engineInit(int keysize, SecureRandom random) {
        }

        @Override
        protected SecretKey engineGenerateKey() {
            if (failGenerateKey) throw new IllegalStateException("QA injected keystore failure");
            byte[] material = new byte[32];
            int seed = 0x5ec01234;
            for (int i = 0; i < material.length; i++) {
                material[i] = (byte) (seed >>> (8 * (i % 4)));
            }
            return new SecretKeySpec(material, "AES");
        }
    }

    public static final class FakeKeyStore extends KeyStoreSpi {
        static final Map<String, Key> keys = new HashMap<>();

        @Override
        public Key engineGetKey(String alias, char[] password)
                throws NoSuchAlgorithmException, UnrecoverableKeyException {
            return keys.get(alias);
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            return null;
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            return null;
        }

        @Override
        public Date engineGetCreationDate(String alias) {
            return new Date(0L);
        }

        @Override
        public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain)
                throws KeyStoreException {
            keys.put(alias, key);
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain)
                throws KeyStoreException {
            throw new KeyStoreException("Not supported by QA fake");
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
            throw new KeyStoreException("Not supported by QA fake");
        }

        @Override
        public void engineDeleteEntry(String alias) throws KeyStoreException {
            keys.remove(alias);
        }

        @Override
        public Enumeration<String> engineAliases() {
            return Collections.enumeration(keys.keySet());
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            return keys.containsKey(alias);
        }

        @Override
        public int engineSize() {
            return keys.size();
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            return keys.containsKey(alias);
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            return false;
        }

        @Override
        public String engineGetCertificateAlias(Certificate cert) {
            return null;
        }

        @Override
        public void engineStore(OutputStream stream, char[] password)
                throws IOException, NoSuchAlgorithmException, CertificateException {
        }

        @Override
        public void engineLoad(InputStream stream, char[] password)
                throws IOException, NoSuchAlgorithmException, CertificateException {
        }
    }
}
