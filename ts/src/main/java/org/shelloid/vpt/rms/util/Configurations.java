/*
 Copyright (c) Shelloid Systems LLP. All rights reserved.
 The use and distribution terms for this software are covered by the
 GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
 which can be found in the file LICENSE at the root of this distribution.
 By using this software in any fashion, you are agreeing to be bound by
 the terms of this license.
 You must not remove this notice, or any other, from this software.
 */
package org.shelloid.vpt.rms.util;

import java.io.*;
import java.util.*;

/* @author Harikrishnan */
public class Configurations {
    public static final Properties defaultProps = new Properties();
    public static final Properties props;
    public static final String WEBSOCKET_PATH = "/websocket";
    public static final int PING_SEND_INTERVAL = 30;
    public static long RX_TX_WINDOW_SIZE_MS;
    public static int MAX_REDIS_CONNECTIONS = 128;
    public static String SERVER_IP;
    
    static {
        defaultProps.put(ConfigParams.ADDON_JARNAME.toString(), "ts-addon.jar");
        defaultProps.put(ConfigParams.CHAIN_FILE.toString(), "shelloid.com.server-chain");
        defaultProps.put(ConfigParams.KEY_FILE.toString(), "shelloid.com.pkcs8");
        defaultProps.put(ConfigParams.RX_TX_WINDOW_SIZE_SECS.toString(), "30");
        defaultProps.put(ConfigParams.LOGBACK_FILE_LOCATION.toString(), "logback.xml");
        defaultProps.put(ConfigParams.TUNNEL_PORT.toString(), "1234");
        defaultProps.put(ConfigParams.JDBC_MAX_ACTIVE.toString(), "50");
        defaultProps.put(ConfigParams.JDBC_MIN_IDLE.toString(), "2");
        defaultProps.put(ConfigParams.REDIS_TIMEOUT.toString(), "1000");
        defaultProps.put(ConfigParams.TUNNEL_RECONNECT_DELAY_MS.toString(), "1000");
        defaultProps.put(ConfigParams.SERVER_SECRET.toString(), "serversecret");
        defaultProps.put(ConfigParams.TUNNEL_FORWARD_MAX_RETTRY_CNT.toString(), "3");
        defaultProps.put(ConfigParams.RELIABLE_MESSENGER_MAX_CONCURRENT_FRAMES.toString(), "1");
        props = new Properties(defaultProps);
    }

    public static void loadPropertiesFile() throws Exception {
        InputStream input = new FileInputStream("rms.cfg");
        props.load(input);
        RX_TX_WINDOW_SIZE_MS = Long.parseLong(get(Configurations.ConfigParams.RX_TX_WINDOW_SIZE_SECS)) * 1000;
        SERVER_IP = get(Configurations.ConfigParams.SERVER_IP);
    }

    public static String get(ConfigParams key) {
        return get(key.toString());
    }
    
    public static String get(String key) {
        return props.getProperty(key);
    }
   
    public enum ConfigParams {
        SERVER_IP("server.ip"),
        TUNNEL_PORT("tunnel.port"),
        SERVER_PORT("server.port"),
        TUNNEL_RECONNECT_DELAY_MS("tunnel.reconnectDelay"),
        REDIS_HOST("redis.host"),
        REDIS_PORT("redis.port"),
        REDIS_PASSWORD("redis.password"),
        REDIS_TIMEOUT("redis.timeout"),
        JDBC_DRIVER("jdbc.driver"),
        JDBC_URL("jdbc.url"),
        JDBC_USERNAME("jdbc.username"),
        JDBC_PASSWORD("jdbc.password"),
        JDBC_MAX_ACTIVE("jdbc.maxActive"),
        JDBC_MIN_IDLE("jdbc.minIdle"),
        RELIABLE_MESSENGER_MAX_CONCURRENT_FRAMES("maxConcurrentFramesToSend"),
        SERVER_SECRET("SERVER_SECRET"),
        TUNNEL_FORWARD_MAX_RETTRY_CNT("TUNNEL_FORWARD_MAX_RETTRY_CNT"),
        RX_TX_WINDOW_SIZE_SECS("RX_TX_WINDOW_SIZE_SECS"),
        LOGBACK_FILE_LOCATION("LOGBACK_FILE_LOCATION"),
        LOG_FILE_PATH("client.logFilePath"),
        CHAIN_FILE("cert.chainFile"),
        KEY_FILE("cert.keyFile"),
        ADDON_CLASSNAME("addon.className"),
        ADDON_JARNAME("addon.jarName");
        private final String text;

        private ConfigParams(final String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
