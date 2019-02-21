package net.i2p.crypto.eddsa;

import java.security.KeyPair;

import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import net.i2p.util.RandomSource;

/**
 *  Default keysize is 256 (Ed25519)
 *
 *  @since 0.9.39
 */
public final class RedKeyPairGenerator extends KeyPairGenerator {

    @Override
    public KeyPair generateKeyPair() {
        if (!initialized)
            initialize(DEFAULT_KEYSIZE, RandomSource.getInstance());

        // 64 bytes
        byte[] seed = new byte[edParams.getCurve().getField().getb()/4];
        random.nextBytes(seed);
        byte[] b = EdDSABlinding.reduce(seed);

        EdDSAPrivateKeySpec privKey = new EdDSAPrivateKeySpec(b, null, edParams);
        EdDSAPublicKeySpec pubKey = new EdDSAPublicKeySpec(privKey.getA(), edParams);

        return new KeyPair(new EdDSAPublicKey(pubKey), new EdDSAPrivateKey(privKey));
    }
}
