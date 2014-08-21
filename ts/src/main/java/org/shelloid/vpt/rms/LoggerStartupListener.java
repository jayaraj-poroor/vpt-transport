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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.ContextAwareBase;
import ch.qos.logback.core.spi.LifeCycle;
import java.io.File;
import org.shelloid.vpt.rms.util.Configurations;

/* @author Harikrishnan */
public class LoggerStartupListener extends ContextAwareBase implements LoggerContextListener, LifeCycle {
    private boolean started = false;

    @Override
    public void start() {
        if (started) {
            return;
        }
        String logFilePath = Configurations.get(Configurations.ConfigParams.LOG_FILE_PATH);
        if (logFilePath == null){
            logFilePath = ".";
        }
        String userHome = new File(logFilePath).getAbsolutePath(); 
        String logFile = "shelloidVPT";
        Context context = getContext();
        System.out.println("Log files clould be found in " + userHome + File.separatorChar + logFile + ".XXX.log");
        context.putProperty("MY_HOME", userHome);
        context.putProperty("LOG_FILE", logFile);
        started = true;
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public boolean isResetResistant() {
        return true;
    }

    @Override
    public void onStart(LoggerContext context) {
    }

    @Override
    public void onReset(LoggerContext context) {
    }

    @Override
    public void onStop(LoggerContext context) {
    }

    @Override
    public void onLevelChange(Logger logger, Level level) {
    }
}