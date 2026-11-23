package android.security.keystore;

/** SEC-02 QA-only Android stub carrying only the alias used by the QA fake provider. */
public final class KeyGenParameterSpec implements java.security.spec.AlgorithmParameterSpec {
    public final String keystoreAlias;

    private KeyGenParameterSpec(String keystoreAlias) {
        this.keystoreAlias = keystoreAlias;
    }

    public static final class Builder {
        private final String alias;

        public Builder(String alias, int purposes) {
            this.alias = alias;
        }

        public Builder setBlockModes(String... blockModes) {
            return this;
        }

        public Builder setEncryptionPaddings(String... paddings) {
            return this;
        }

        public Builder setKeySize(int keySize) {
            return this;
        }

        public KeyGenParameterSpec build() {
            return new KeyGenParameterSpec(alias);
        }
    }
}
