package org.zeromq;

import java.io.File;
import java.io.IOException;

import org.zeromq.ZMQ.Curve.KeyPair;
import org.zeromq.util.ZMetadata;

/**
 *
    The ZCert class provides a way to create and work with security
    certificates for the ZMQ CURVE mechanism. A certificate contains a
    public + secret key pair, plus metadata. It can be used as a
    temporary object in memory, or persisted to disk.
    <p>
    To exchange certificates, send the public file via some secure route.
    Certificates are not signed but are text files that can be verified by
    eye.
    <p>
    Certificates are stored in the <a href="https://rfc.zeromq.org/spec:4/ZPL/">ZeroMQ Property Language format</a>.
    <br>
    They have two sections, "metadata" and "curve".
    <br>
    The first contains a list of 'name = value' pairs, one per line.
    Values may be enclosed in quotes.
    <br>
    The curve section has a 'public-key = key-value' and, for secret certificates, a
    'secret-key = key-value' line.
    <br>
    The key-value is a {@link zmq.util.Z85 Z85-encoded CURVE key}.
 *
 */
public class ZCert
{
    private final byte[]    publicKey;                  //  Public key in binary
    private final byte[]    secretKey;                  //  Secret key in binary
    private final String    publicTxt;                  //  Public key in Z85 text
    private final String    secretTxt;                  //  Secret key in Z85 text
    private final ZMetadata metadata = new ZMetadata(); //  Certificate metadata

    public ZCert(String publickey)
    {
        this(publickey, null);
    }

    public ZCert()
    {
        KeyPair keypair = ZMQ.Curve.generateKeyPair();
        publicKey = ZMQ.Curve.z85Decode(keypair.publicKey);
        publicTxt = keypair.publicKey;
        secretKey = ZMQ.Curve.z85Decode(keypair.secretKey);
        secretTxt = keypair.secretKey;
    }

    public ZCert(byte[] publickey, byte[] secretkey)
    {
        publicKey = publickey;
        publicTxt = ZMQ.Curve.z85Encode(publickey);
        secretKey = secretkey;
        secretTxt = ZMQ.Curve.z85Encode(secretkey);
    }

    public ZCert(String publickey, String secretkey)
    {
        assert (publickey != null);
        if (publickey.length() == ZMQ.Curve.KEYSIZE) {
            // in binary-format
            publicKey = publickey.getBytes(ZMQ.CHARSET);
            publicTxt = ZMQ.Curve.z85Encode(publicKey);
        }
        else {
            assert (publickey.length() == ZMQ.Curve.KEYSIZE_Z85);
            // Z85-Coded
            publicKey = ZMQ.Curve.z85Decode(publickey);
            publicTxt = publickey;
        }
        if (secretkey == null) {
            secretKey = null;
            secretTxt = null;
        }
        else if (secretkey.length() == ZMQ.Curve.KEYSIZE) {
            // in binary-format
            secretKey = secretkey.getBytes(ZMQ.CHARSET);
            secretTxt = ZMQ.Curve.z85Encode(secretKey);
        }
        else {
            assert (secretkey.length() == ZMQ.Curve.KEYSIZE_Z85);
            // Z85-Coded
            secretKey = ZMQ.Curve.z85Decode(secretkey);
            secretTxt = secretkey;
        }
    }

    public byte[] getPublicKey()
    {
        return publicKey;
    }

    public byte[] getSecretKey()
    {
        return secretKey;
    }

    public String getPublicKeyAsZ85()
    {
        return publicTxt;
    }

    public String getSecretKeyAsZ85()
    {
        return secretTxt;
    }

    public void apply(ZMQ.Socket socket)
    {
        socket.setCurvePublicKey(publicKey);
        socket.setCurveSecretKey(secretKey);
    }

    public ZMetadata getMetadata()
    {
        return metadata;
    }

    public void setMeta(String key, String value)
    {
        metadata.set(key, value);
    }

    private void add(ZMetadata meta, ZConfig config)
    {
        for (String key : meta.keySet()) {
            config.putValue("metadata/" + key, meta.get(key));
        }
    }

    /**
     * Saves the public  key to a file.
     * @param filename the path of the file to save the certificate into.
     * @throws IOException if unable to save the file.
     */
    public File savePublic(String filename) throws IOException
    {
        ZConfig conf = new ZConfig("root", null);
        add(metadata, conf);
        conf.addComment("   ZeroMQ CURVE Public Certificate");
        conf.addComment("   Exchange securely, or use a secure mechanism to verify the contents");
        conf.addComment("   of this file after exchange. Store public certificates in your home");
        conf.addComment("   directory, in the .curve subdirectory.");
        conf.putValue("/curve/public-key", publicTxt);
        return conf.save(filename);
    }

    /**
     * Saves the public and secret keys to a file.
     * @param filename the path of the file to save the certificate into.
     * @throws IOException if unable to save the file.
     */
    public File saveSecret(String filename) throws IOException
    {
        ZConfig conf = new ZConfig("root", null);
        add(metadata, conf);
        conf.addComment("   ZeroMQ CURVE **Secret** Certificate");
        conf.addComment("   DO NOT PROVIDE THIS FILE TO OTHER USERS nor change its permissions.");
        conf.putValue("/curve/public-key", publicTxt);
        conf.putValue("/curve/secret-key", secretTxt);
        return conf.save(filename);
    }
}
