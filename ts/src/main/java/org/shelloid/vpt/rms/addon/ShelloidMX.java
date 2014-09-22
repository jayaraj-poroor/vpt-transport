/*
Copyright (c) Shelloid Systems LLP. All rights reserved.
The use and distribution terms for this software are covered by the
GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
which can be found in the file LICENSE at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.
*/
package org.shelloid.vpt.rms.addon;

import java.sql.Connection;
import java.util.Properties;
import org.shelloid.common.DeferredRedisTransaction;
import org.shelloid.common.exceptions.ShelloidNonRetriableException;
import org.shelloid.common.messages.ShelloidMessageModel.*;
import org.shelloid.vpt.rms.ConnectionMetadata;
import redis.clients.jedis.Jedis;

/*
 * @author Harikrishnan
 */
public interface ShelloidMX {
    public void init(Properties props) throws ShelloidNonRetriableException;
    public void onAgentAuth(ConnectionMetadata cm, long devId, String serverIp) throws ShelloidNonRetriableException;
    public void onAgentDisconnect(ConnectionMetadata cm) throws ShelloidNonRetriableException;
    public void onClosePortMap(Connection conn, long portMapId) throws ShelloidNonRetriableException;
    public void onOpenPortMap(Connection conn, long portMapId, ShelloidMessage msg) throws ShelloidNonRetriableException;
    public void onAgentTunnelMsg (ConnectionMetadata cm, ConnectionMetadata remoteCm, long remoteDevId, ShelloidMessage msg, int retries, Jedis jedis) throws ShelloidNonRetriableException;
    public void onGeneratedReliableMsg(ConnectionMetadata cm, ConnectionMetadata remoteCm,  long deviceId, ShelloidMessage msg, Jedis jedis, DeferredRedisTransaction tx) throws ShelloidNonRetriableException;
}
