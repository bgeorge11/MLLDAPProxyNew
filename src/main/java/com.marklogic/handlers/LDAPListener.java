package com.marklogic.handlers;

import com.marklogic.configuration.ApplicationConfig;
import com.marklogic.configuration.ListenersConfig;
import com.marklogic.configuration.ServersConfig;
import com.marklogic.configuration.SetsConfig;
import com.unboundid.ldap.listener.LDAPListener;
import com.unboundid.ldap.listener.LDAPListenerConfig;
import com.unboundid.ldap.listener.LDAPListenerRequestHandler;
import com.unboundid.ldap.sdk.*;
import com.unboundid.util.Validator;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import org.aeonbits.owner.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.security.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Created by mwarnes on 29/01/2017.
 */
@Component
class LDAPlistener implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LDAPlistener.class);

    @Override
    public void run(ApplicationArguments applicationArguments) throws Exception {

        ApplicationConfig cfg = ConfigFactory.create(ApplicationConfig.class);

        logger.debug("ldap.debug flag: " + cfg.ldapDebug());
        if (cfg.ldapDebug()) {
            System.setProperty("com.unboundid.ldap.sdk.debug.enabled","true");
            System.setProperty("com.unboundid.ldap.sdk.debug.type","ldap");
        }

        for (String l : cfg.listeners()) {
            logger.debug("Listener: " + l);
            Map expVars = new HashMap();
            expVars.put("listener", l);
            ListenersConfig listenerCfg = ConfigFactory
                    .create(ListenersConfig.class, expVars);

            logger.debug("IP Address: " + listenerCfg.listenerIpAddress());
            logger.debug("Port: " + listenerCfg.listenerPort());
            logger.debug("Request handler: " + listenerCfg.listenerRequestHandler());
            for (String s : listenerCfg.listenerLDAPSet()) {
                logger.debug("ServerSet: " + s);
            }

            ServerSet serverSet = buildServerSet(listenerCfg.listenerLDAPSet(), listenerCfg.listenerLDAPMode());

            logger.debug(serverSet.toString());

            if (listenerCfg.secureListener()) {
                Constructor c = Class.forName(listenerCfg.listenerRequestHandler()).getDeclaredConstructor(ServerSet.class, String.class);
                LDAPListenerRequestHandler mlh = (LDAPListenerRequestHandler) c.newInstance(serverSet,listenerCfg.listenerAuthenticator());
                LDAPListenerConfig listenerConfig = new LDAPListenerConfig(listenerCfg.listenerPort(), mlh);
                ServerSocketFactory ssf = createServerSocketFactory(listenerCfg);
                listenerConfig.setServerSocketFactory(ssf);
                LDAPListener listener = new LDAPListener(listenerConfig);
                listener.startListening();
            } else {
                Constructor c = Class.forName(listenerCfg.listenerRequestHandler()).getDeclaredConstructor(ServerSet.class, String.class);
                LDAPListenerRequestHandler mlh = (LDAPListenerRequestHandler) c.newInstance(serverSet,listenerCfg.listenerAuthenticator());
                LDAPListenerConfig listenerConfig = new LDAPListenerConfig(listenerCfg.listenerPort(), mlh);
                LDAPListener listener = new LDAPListener(listenerConfig);
                listener.startListening();
            }

            logger.info("Listening on: " + listenerCfg.listenerIpAddress() + ":" + listenerCfg.listenerPort() + " ( " + listenerCfg.listenerDescription() + " )");
        }

    }

    private ServerSet buildServerSet(String[] serverSetsList, String mode) throws GeneralSecurityException, IOException {
        logger.debug("Building server sets");

        ServerSet returnSet=null;
        List<ServerSet> sets = new ArrayList();

        for (String set : serverSetsList) {
            logger.debug("ServerSet: " + set);

            ServerSet ss= null;

            List<String> hostAddresses = new ArrayList<>();
            List<Integer> hostPorts = new ArrayList<Integer>();

            Map setVars = new HashMap();
            setVars.put("serverSet", set);
            SetsConfig setsCfg = ConfigFactory
                    .create(SetsConfig.class, setVars);

            for (String server : setsCfg.servers()) {
                Map serverVars = new HashMap();
                serverVars.put("server", server);
                ServersConfig serverCfg = ConfigFactory
                        .create(ServersConfig.class, serverVars);

                logger.debug("LDAP Server host: " + serverCfg.serverHost());
                logger.debug("LDAP Server Port: " + serverCfg.serverPort());

                hostAddresses.add(serverCfg.serverHost());
                hostPorts.add(serverCfg.serverPort());
            }

            // Convert Addresses and Port List to Array
            int[] intPorts = new int[hostPorts.size()];
            for (int i = 0; i < intPorts.length; i++)
                intPorts[i] = hostPorts.get(i);
            String[] strAddresses = new String[hostAddresses.size()];
            for (int i = 0; i < strAddresses.length; i++)
                strAddresses[i] = hostAddresses.get(i);

            if (mode.equalsIgnoreCase("SINGLE")) {
                if (setsCfg.serverSetSecure()) {
                    ss = new SingleServerSet(hostAddresses.get(0), hostPorts.get(0), createSecureSocketFactory(setsCfg));
                } else {
                    ss = new SingleServerSet(hostAddresses.get(0), hostPorts.get(0));
                }
            } else if (mode.equalsIgnoreCase("ROUNDROBIN")) {
                if (setsCfg.serverSetSecure()) {
                    ss = new RoundRobinServerSet(strAddresses, intPorts, createSecureSocketFactory(setsCfg));
                } else {
                    ss = new RoundRobinServerSet(strAddresses, intPorts);
                }
            } else if (mode.equalsIgnoreCase("FAILOVER")) {
                if (setsCfg.serverSetSecure()) {
                    ss = new FailoverServerSet(strAddresses, intPorts, createSecureSocketFactory(setsCfg));
                } else {
                    ss = new FailoverServerSet(strAddresses, intPorts);
                }
            } else if (mode.equalsIgnoreCase("FASTEST")) {
                if (setsCfg.serverSetSecure()) {
                    ss = new FastestConnectServerSet(strAddresses, intPorts, createSecureSocketFactory(setsCfg));
                } else {
                    ss = new FastestConnectServerSet(strAddresses, intPorts);
                }
            } else if (mode.equalsIgnoreCase("FEWEST")) {
                if (setsCfg.serverSetSecure()) {
                    ss = new FewestConnectionsServerSet(strAddresses, intPorts, createSecureSocketFactory(setsCfg));
                } else {
                    ss = new FewestConnectionsServerSet(strAddresses, intPorts);
                }
            } else if (mode.equalsIgnoreCase("ROUNDROBINDNS")) {
                RoundRobinDNSServerSet.AddressSelectionMode selectionMode =
                        RoundRobinDNSServerSet.AddressSelectionMode.ROUND_ROBIN;
                long cacheTimeoutMillis = 3600000L; // 1 hour
                String providerURL = "dns:";
                SocketFactory socketFactory = null;
                LDAPConnectionOptions connectionOptions = null;
                if (setsCfg.serverSetSecure()) {
                    ss = new RoundRobinDNSServerSet(hostAddresses.get(0),
                            hostPorts.get(0), selectionMode, cacheTimeoutMillis, providerURL, createSecureSocketFactory(setsCfg),
                            connectionOptions);
                } else {
                    ss = new RoundRobinDNSServerSet(hostAddresses.get(0),
                            hostPorts.get(0), selectionMode, cacheTimeoutMillis, providerURL, socketFactory,
                            connectionOptions);
                }
            }

            sets.add(ss);

        }

        Validator.ensureNotNull(sets);

        // If there is more than one set then create a FailoverServerSet from the list.
        // This will allow a user to configure a mixed set to allow greater connectivity options
        // including the ability to have secure and insecure back end connections.
        if (sets.size()>1) {
            returnSet = new FailoverServerSet(sets.toArray(new ServerSet[sets.size()]));
        } else {
            returnSet=sets.get(0);
        }

        return returnSet;
    }

    private SSLSocketFactory createSecureSocketFactory(SetsConfig cfg) throws GeneralSecurityException, IOException {
        logger.debug("Creating SSL Socket Factory.");

        SSLUtil sslUtil = getSslUtil(cfg.serverSetKeyStore(),cfg.serverSetKeyStorePassword(),cfg.serverSetTrustStore(),cfg.serverSetTrustStorePassword());

        return sslUtil.createSSLSocketFactory();

    }

    private ServerSocketFactory createServerSocketFactory(ListenersConfig cfg) throws Exception {
        logger.debug("Creating Server Socket Factory.");

        if (cfg.listenerKeyStore().isEmpty() || cfg.listenerKeyStorePassword().isEmpty() ) {
            throw new Exception("Unable to create secure listener without keystore and password.");
        }

        SSLUtil sslUtil = getSslUtil(cfg.listenerKeyStore(),cfg.listenerKeyStorePassword(),cfg.listenerTrustStore(),cfg.listenerTrustStorePassword());

        return sslUtil.createSSLServerSocketFactory();

    }

    private SSLUtil getSslUtil(String keystore, String keystorepw, String truststore, String truststorepw) throws GeneralSecurityException, IOException {
        logger.debug("Creating SSLUtil.");
        SSLUtil sslUtil = null;

        KeyManager km = null;
        TrustManager tm = null;

        logger.debug("Keystore: " + keystore);
        logger.debug("Keystore password: " + keystorepw);
        logger.debug("Truststore: " + truststore);
        logger.debug("Truststore password: " + truststorepw);

        if (!keystore.isEmpty() && !keystorepw.isEmpty()) {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(keystore), keystorepw.toCharArray());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(ks, keystorepw.toCharArray());
            km = kmf.getKeyManagers()[0];
        }

        if (!truststore.isEmpty() && !truststorepw.isEmpty()) {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(truststore), truststorepw.toCharArray());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(ks);
            tm = tmf.getTrustManagers()[0];
        }

        if (km!=null & tm==null) {
            logger.debug("No Trust managers created using defined KeyManager & TrustAllTrustManager.");
            sslUtil = new SSLUtil(km,new TrustAllTrustManager());
        } else if (km!=null & tm!=null) {
            logger.debug("Using configured KeyManager & TrustManager.");
            sslUtil = new SSLUtil(km,tm);
        } else if (km==null & tm!=null) {
            logger.debug("Using configured TrustManager.");
            sslUtil = new SSLUtil(tm);
        } else {
            logger.debug("Using default TrustAllTrustManager.");
            sslUtil = new SSLUtil(new TrustAllTrustManager());
        }

        return sslUtil;
    }


}
