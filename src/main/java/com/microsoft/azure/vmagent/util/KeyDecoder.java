package com.microsoft.azure.vmagent.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import org.apache.commons.lang.NotImplementedException;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;

public final class KeyDecoder {
    private static final int CAPACITY = 4;

    private KeyDecoder() {
    }

    public static String getRsaPublicKey(String privateKey, String privateSshKeyPassphrase) throws IOException {

        PEMKeyPair keyPair;
        RSAPublicKey rsaPubKey;
        Object keyObj = new PEMParser(new StringReader(privateKey)).readObject();

        // Key may be encrypted
        if (keyObj instanceof PEMKeyPair) {
            keyPair = (PEMKeyPair) keyObj;
        } else {
            PEMEncryptedKeyPair encKeyPair = (PEMEncryptedKeyPair) keyObj;
            PEMDecryptorProvider decryptionProv = new JcePEMDecryptorProviderBuilder()
                    .build(privateSshKeyPassphrase.toCharArray());
            keyPair = encKeyPair.decryptKeyPair(decryptionProv);
        }

        try {
            rsaPubKey = (RSAPublicKey) new JcaPEMKeyConverter().getPublicKey(keyPair.getPublicKeyInfo());
        } catch (ClassCastException e) {
            throw new NotImplementedException("Only RSA SSH keys are currently supported", e);
        }

        byte[] pubKeyBody = getSshPublicKeyBody(rsaPubKey);
        String b64PubkeyBody = new String(java.util.Base64.getEncoder().encode(pubKeyBody), StandardCharsets.UTF_8);
        return "ssh-rsa " + b64PubkeyBody;
    }

    private static byte[] getSshPublicKeyBody(RSAPublicKey rsaPubKey) throws IOException {
        byte[] algorithmName = "ssh-rsa".getBytes(StandardCharsets.UTF_8);
        byte[] algorithmNameLength = ByteBuffer.allocate(CAPACITY).putInt(algorithmName.length).array();
        byte[] e = rsaPubKey.getPublicExponent().toByteArray(); // Usually 65,537
        byte[] eLength = ByteBuffer.allocate(CAPACITY).putInt(e.length).array();
        byte[] m = rsaPubKey.getModulus().toByteArray();
        byte[] mLength = ByteBuffer.allocate(CAPACITY).putInt(m.length).array();

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            os.write(algorithmNameLength);
            os.write(algorithmName);
            os.write(eLength);
            os.write(e);
            os.write(mLength);
            os.write(m);

            return os.toByteArray();
        }
    }
}
