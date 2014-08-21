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

import org.shelloid.common.Database;
import org.shelloid.common.exceptions.ShelloidNonRetriableException;
import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import redis.clients.jedis.Jedis;

/* @author Harikrishnan */
public class RMSUtils {
    private static final RMSUtils utils = new RMSUtils();
    
    private RMSUtils(){
        
    }
    
    public static RMSUtils getInstance() {
         return utils;
    }

    public String getRemoteDeviceId(Connection conn, boolean isSvcSide, String portMapId) throws SQLException, ShelloidNonRetriableException {
        String remoteDevId = null;
        ArrayList<HashMap<String, Object>> list = Database.getResult(conn, "SELECT mapped_dev_id, svc_dev_id FROM port_maps WHERE id = ?", new Object[]{portMapId});
        if (list.size() > 0) {
            if (isSvcSide) {
                remoteDevId = list.get(0).get("mapped_dev_id").toString();
            } else {
                remoteDevId = list.get(0).get("svc_dev_id").toString();
            }
            if (remoteDevId == null) {
                throw new ShelloidNonRetriableException("The other device not connected.");
            }
        } else {
            throw new ShelloidNonRetriableException("Invalid port map ID: " + portMapId);
        }
        return remoteDevId;
    }

}
