/*
Copyright (c) Shelloid Systems LLP. All rights reserved.
The use and distribution terms for this software are covered by the
GNU Affero General Public License 3.0 (http://www.gnu.org/licenses/agpl-3.0.html)
which can be found in the file LICENSE at the root of this distribution.
By using this software in any fashion, you are agreeing to be bound by
the terms of this license.
You must not remove this notice, or any other, from this software.
*/

package org.shelloid.common;

import org.shelloid.ptcp.NetworkConstants;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.codec.binary.Base64;

public class ShelloidUtil {

    private static ShelloidUtil instance;

    private ShelloidUtil() {

    }

    public static ShelloidUtil getInstance() {
        if (instance == null) {
            instance = new ShelloidUtil();
        }
        return instance;
    }

    public String getValByAttributeTypeFromIssuerDN(String dn, String attributeType) {
        String[] dnSplits = dn.split(",");
        for (String dnSplit : dnSplits) {
            if (dnSplit.contains(attributeType)) {
                String[] cnSplits = dnSplit.trim().split("=");
                if (cnSplits[1] != null) {
                    return cnSplits[1].trim();
                }
            }
        }
        return "";
    }

    public static int getMaxFrameSize() {
        final int maxFrameOverhead = 4 * 1024;
        int s = ((int) NetworkConstants.MAX_PACKET) * 2 + maxFrameOverhead;
        //System.out.println("MAX-FRAME-SIZE: " + s);
        return s;
    }

    public SSLSocketFactory getSslSocketFactory() {
        try {
            TrustManager[] tms = new TrustManager[]{new ShelloidX509TrustManager()};
            SSLContext sslCtx = SSLContext.getInstance("TLS");
            sslCtx.init(null, tms, null);
            return sslCtx.getSocketFactory();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public String getRemoteIp(Channel ch) {
        return ((InetSocketAddress) ch.remoteAddress()).getAddress().getHostAddress();
    }

    public int getLocalPort(Channel ch) {
        return ((InetSocketAddress) ch.localAddress()).getPort();
    }

    public byte[] encodeBase64(byte[] data) {
        return Base64.encodeBase64(data);
    }

    public byte[] decodeBase64(byte[] data) {
        return Base64.decodeBase64(data);
    }

    public long ipToLong(String ipAddress) {
        long result = 0;
        String[] ipAddressInArray = ipAddress.split("\\.");
        for (int i = 3; i >= 0; i--) {
            long ip = Long.parseLong(ipAddressInArray[3 - i]);
            result |= ip << (i * 8);
        }
        return result;
    }

    public String longToIp(long ip) {
        StringBuilder result = new StringBuilder(15);
        for (int i = 0; i < 4; i++) {
            result.insert(0, Long.toString(ip & 0xff));
            if (i < 3) {
                result.insert(0, '.');
            }
            ip = ip >> 8;
        }
        return result.toString();
    }
}

class ShelloidX509TrustManager implements X509TrustManager {

    ArrayList<X509Certificate> certs;

    public ShelloidX509TrustManager() {
        certs = new ArrayList<X509Certificate>();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        System.out.println("checkClientTrusted called");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
        for (X509Certificate cert : certs) {
            if (cert.getIssuerDN().getName().contains("Shelloid")) {
                this.certs.add(cert);
            } else {
                throw new CertificateException("Certificate DN doesn't contains shelloid");
            }
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return (X509Certificate[]) certs.toArray();
    }
}
