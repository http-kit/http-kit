package org.httpkit.client;

import javax.net.ssl.*;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;


public class ClientSslEngineFactory {

    private static final String PROTOCOL = "TLS";
    private static SSLContext clientContext = null;

    private static synchronized void initContext() {
        // There's a chance another thread was waiting to enter this
        // method before clientContext was initialized but enters after
        // initialization has finished successfully. If that happens,
        // return without doing anything.
        if (clientContext != null) {return;}
        try {
            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(null, TrustManagerFactory.getTrustManagers(), null);
            clientContext = context;
        } catch (Exception e) {
            throw new Error("Failed to initialize the client-side SSLContext", e);
        }
    }

    public static SSLEngine trustAnybody() {
        // Enter synchronized block only when uninitialized
        if (clientContext == null) {
            initContext();
        }
        SSLEngine engine = clientContext.createSSLEngine();
        engine.setUseClientMode(true);
        return engine;
    }
}


class TrustManagerFactory extends TrustManagerFactorySpi {

    private static final TrustManager DUMMY_TRUST_MANAGER = new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        public void checkClientTrusted(X509Certificate[] chain,
                                       String authType) throws CertificateException {
            // Always trust
        }

        public void checkServerTrusted(X509Certificate[] chain,
                                       String authType) throws CertificateException {
            // Always trust
        }
    };

    public static TrustManager[] getTrustManagers() {
        return new TrustManager[]{DUMMY_TRUST_MANAGER};
    }

    @Override
    protected TrustManager[] engineGetTrustManagers() {
        return getTrustManagers();
    }

    @Override
    protected void engineInit(KeyStore keystore) throws KeyStoreException {
        // Unused
    }

    @Override
    protected void engineInit(
            ManagerFactoryParameters managerFactoryParameters)
            throws InvalidAlgorithmParameterException {
        // Unused
    }
}
