package com.microsoft.azure.vmagent.util;

import com.sshtools.common.publickey.InvalidPassphraseException;
import com.sshtools.common.publickey.SshKeyUtils;
import com.sshtools.common.ssh.SshException;
import com.sshtools.common.ssh.components.SshKeyPair;
import java.io.IOException;
import java.util.Base64;

public final class KeyDecoder {
    private KeyDecoder() {
    }

    public static String getPublicKey(String privateKey, String privateSshKeyPassphrase) throws IOException,
            InvalidPassphraseException,
            SshException {
        SshKeyPair keyPair = SshKeyUtils.getPrivateKey(privateKey, privateSshKeyPassphrase);

        String publisKey = Base64.getEncoder().encodeToString(keyPair.getPublicKey().getEncoded());
        return keyPair.getPublicKey().getAlgorithm() + " " + publisKey;
    }

}
