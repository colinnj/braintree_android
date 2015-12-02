package com.paypal.android.sdk.onetouch.core.encryption;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TODO - check older phones to verify they have AES/RSA.  If not, we'll need to package
 * BouncyCastle with the SDK.
 * <p>
 * http://www.unwesen.de/2011/06/12/encryption-on-android-bouncycastle/
 */
public class OtcCrypto {
    private static final String TAG = OtcCrypto.class.getSimpleName();
    private static final int ENCRYPTION_KEY_SIZE = 32;
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int NONCE_SIZE = 16;
    private static final String AES_CTR_ALGO = "AES/CTR/NoPadding";
    private static final String RSA_ALGO = "RSA/ECB/OAEPWithSHA1AndMGF1Padding";
    private static final int AES_KEY_SIZE = 16;
    private static final int DIGEST_SIZE = 32;
    private static final int PUBLIC_KEY_SIZE = 256;

    private byte[] dataDigest(byte[] data, byte[] key)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac sha256HMAC = Mac.getInstance(HMAC_SHA256);
        SecretKeySpec digestKey = new SecretKeySpec(key, HMAC_SHA256);
        sha256HMAC.init(digestKey);
        byte[] output = sha256HMAC.doFinal(data);
        return output;
    }

    public byte[] generateRandom256BitKey() {
        return EncryptionUtils.generateRandomData(ENCRYPTION_KEY_SIZE);
    }

    public byte[] encryptAESCTRData(byte[] plainData, byte[] key)
            throws NoSuchPaddingException, NoSuchAlgorithmException,
            InvalidAlgorithmParameterException, InvalidKeyException, BadPaddingException,
            IllegalBlockSizeException {
        // setup key, digest key and nonce
        byte[] encryptionKey = new byte[AES_KEY_SIZE];
        System.arraycopy(key, 0, encryptionKey, 0, AES_KEY_SIZE);
        byte[] digestKey = new byte[AES_KEY_SIZE];
        System.arraycopy(key, AES_KEY_SIZE, digestKey, 0, AES_KEY_SIZE);
        byte[] nonceData = EncryptionUtils.generateRandomData(NONCE_SIZE);

        // setup encryption
        IvParameterSpec nonceSpec = new IvParameterSpec(nonceData);
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
        Cipher cipher = Cipher.getInstance(AES_CTR_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, nonceSpec);

        // encrypt in one go
        byte[] cipherData = cipher.doFinal(plainData);

        // now we need to add nonce and data together and sign
        byte[] dataToSign = new byte[NONCE_SIZE + cipherData.length];
        System.arraycopy(nonceData, 0, dataToSign, 0, NONCE_SIZE);
        System.arraycopy(cipherData, 0, dataToSign, NONCE_SIZE, cipherData.length);

        // calculate signature
        byte[] signature = dataDigest(dataToSign, digestKey);

        // now combine all of this together and return
        byte[] output = new byte[signature.length + dataToSign.length];
        System.arraycopy(signature, 0, output, 0, signature.length);
        System.arraycopy(dataToSign, 0, output, signature.length, dataToSign.length);

        // output is signature + nonce + encrypted blob

        return output;
    }

    public byte[] encryptRSAData(byte[] plainData, Certificate certificate)
            throws NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException,
            InvalidKeyException, BadPaddingException, IllegalBlockSizeException,
            CertificateException, InvalidEncryptionDataException {
        // data cannot be bigger than 256 bytes
        if (plainData.length > PUBLIC_KEY_SIZE) {
            throw new InvalidEncryptionDataException("Data is too large for public key encryption");
        }

        PublicKey publicKey = certificate.getPublicKey();

        Cipher rsaCipher = Cipher.getInstance(RSA_ALGO);
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] output = rsaCipher.doFinal(plainData);
        return output;
    }

    public byte[] decryptAESCTRData(byte[] cipherData, byte[] key)
            throws IllegalBlockSizeException, InvalidKeyException, NoSuchAlgorithmException,
            IllegalArgumentException, InvalidAlgorithmParameterException, NoSuchPaddingException,
            BadPaddingException, InvalidEncryptionDataException {
        // we should have at least 1 byte of data
        if (cipherData.length < DIGEST_SIZE + NONCE_SIZE) {
            throw new InvalidEncryptionDataException("data is too small");
        }
        // first 16 bytes is encryption key, 2nd 16 bytes is digest key
        byte[] encryptionKey = new byte[AES_KEY_SIZE];
        System.arraycopy(key, 0, encryptionKey, 0, AES_KEY_SIZE);
        byte[] digestKey = new byte[AES_KEY_SIZE];
        System.arraycopy(key, AES_KEY_SIZE, digestKey, 0, AES_KEY_SIZE);

        // extract signature it is 32 bytes
        byte[] signature = new byte[DIGEST_SIZE];
        System.arraycopy(cipherData, 0, signature, 0, DIGEST_SIZE);

        // extract the rest to calculate digest and compare it to the signature
        byte[] signedData = new byte[cipherData.length - DIGEST_SIZE];
        System.arraycopy(cipherData, DIGEST_SIZE, signedData, 0, cipherData.length - DIGEST_SIZE);
        byte[] digest = dataDigest(signedData, digestKey);
        if (!EncryptionUtils.isEqual(digest, signature)) {
            throw new IllegalArgumentException("Signature mismatch");
        }

        // read nonce
        byte[] nonceData = new byte[NONCE_SIZE];
        System.arraycopy(signedData, 0, nonceData, 0, NONCE_SIZE);

        // init nonce and decrypt
        IvParameterSpec nonceSpec = new IvParameterSpec(nonceData);
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");

        Cipher cipher = Cipher.getInstance(AES_CTR_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, nonceSpec);
        byte[] output = cipher.doFinal(signedData, NONCE_SIZE, signedData.length - NONCE_SIZE);
        return output;
    }

}