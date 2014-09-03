/*
Copyright (c) Shelloid Systems LLP. All rights reserved.
The use and distribution terms for this software are covered by the
GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
which can be found in the file LICENSE at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.
*/
package org.shelloid.vpt.rms;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import org.shelloid.vpt.rms.addon.ShelloidMX;
import org.shelloid.vpt.rms.server.VPTServer;
import org.shelloid.vpt.rms.util.Configurations;
import static org.shelloid.vpt.rms.util.Configurations.get;
import org.shelloid.vpt.rms.util.Platform;
import static org.shelloid.vpt.rms.util.Platform.shelloidLogger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;

/* @author Harikrishnan */
public class App {

    private static RedisSubscriber sub;
    public static String serverSecret;

    /*
    public static void referDynamicClasses() {
        //referring to dynamically loaded classes referred to in XML config files - so that 
        //minimizeJar option in pom.xml will not remove these classes from jar file
        ch.qos.logback.core.ConsoleAppender.class.toString();
        com.mysql.jdbc.Driver.class.toString();
        org.apache.commons.pool2.impl.DefaultEvictionPolicy.class.toString();
        org.apache.commons.pool2.impl.DefaultEvictionPolicy.class.toString();
        ch.qos.logback.core.rolling.TimeBasedRollingPolicy.class.toString();
    }
    */
    
    public static void main(String[] args) throws Exception {
        /*referDynamicClasses();*/
        Configurations.loadPropertiesFile();
        loadLogbakConfigFile(get(Configurations.ConfigParams.LOGBACK_FILE_LOCATION));
        sub = new RedisSubscriber();
        subscribeToRedis();
        Platform.shelloidLogger.warn("Shelloid VPT Server " + getVersion() + " started.");
        ShelloidMX mx = null;
        String className = get(Configurations.ConfigParams.ADDON_CLASSNAME);
        if (className != null){
            String jarName = get(Configurations.ConfigParams.ADDON_JARNAME);
            try {
                URLClassLoader child = new URLClassLoader (new URL[]{new File(jarName).toURI().toURL()});
                mx = (ShelloidMX) Class.forName(className, true, child).newInstance();
                Platform.shelloidLogger.warn("Shelloid addon loaded.");
            } catch (Exception ex){
                Platform.shelloidLogger.error("Failed to load class " + className, ex);
            }
        } else {
            Platform.shelloidLogger.warn("No addon loading.");
        }
        if (mx != null){
            mx.init(Configurations.props);
        }
        new VPTServer(Integer.parseInt(get(Configurations.ConfigParams.SERVER_PORT))).run(mx);
        sub.unsubscribe();
    }
    
    public static String getVersion(){
        Package p = App.class.getPackage();
        String version = p.getImplementationVersion();
        if (version == null) {
            version = "devTest";
        }
        return version;
    }
    
    public static void subscribeToRedis() {
        final Jedis subscriberJedis = new Jedis(get(Configurations.ConfigParams.REDIS_HOST), Integer.parseInt(Configurations.get(Configurations.ConfigParams.REDIS_PORT)));
        subscriberJedis.auth(get(Configurations.ConfigParams.REDIS_PASSWORD));
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    subscriberJedis.subscribe(sub, Configurations.SERVER_IP);
                } catch (Exception e) {
                    shelloidLogger.error("Subscribing failed.", e);
                }
            }
        }).start();
    }

    public static void queryNewMsgs(String deviceId) {
        sub.handleNewMsg(deviceId);
    }

    public static void unsubscribe(Jedis j) {
        sub.unsubscribe();
    }


    private static void loadLogbakConfigFile(String logFilePath) {
        System.out.println("Reading logger configuration file from " + logFilePath);
        // assume SLF4J is bound to logback in the current environment
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            // Call context.reset() to clear any previous configuration, e.g. default 
            // configuration. For multi-step configuration, omit calling context.reset().
            context.reset();
            configurator.doConfigure(new File(logFilePath));
        } catch (JoranException je) {
            // StatusPrinter will handle this
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);
    }
}
