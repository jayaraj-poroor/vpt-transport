package org.shelloid.common.messages;

/* @author Harikrishnan */
public interface MessageTypes 
{
    public static final String NEW_MSG = "NEW_MSG";
    public static final String ACK = "ACK";
    public static final String ERROR = "ERROR";
    public static final String NAUTH = "NAUTH";
    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String REGN = "REGN";
    public static final String URGENT = "URGENT";
    public static final String SAVE_QUERY = "SAVE_QUERY";
    public static final String START_STREAM = "START_STREAM";
    public static final String STOP_STREAM = "STOP_STREAM";
    public static final String STREAM_MSG = "STREAM_MSG";
    public static final String STREAM_LOG = "STREAM_LOG";
    public static final String STREAM_DEBUG_LOG = "STREAM_DEBUG_LOG";
    public static final String NODEMSG = "NODEMSG";
    public static final String REMOVE_CONTROLLED_POLL = "REMOVE_CONTROLLED_POLL";
    public static final String ADD_CONTROLLED_POLL = "ADD_CONTROLLED_POLL";
    public static final String TUNNEL = "TUNNEL";
    public static final String TUNNEL_FORWARD = "TUNNEL_FORWARD";
    public static final String TUNNEL_FORWARD_ERROR = "TUNNEL_FORWARD_ERROR";
    public static final String START_LISTENING = "START_LISTENING";
    public static final String OPEN_PORT = "OPEN_PORT";
    public static final String CLOSE_PORT = "CLOSE_PORT";
    public static final String STOP_LISTEN = "STOP_LISTEN";  
    public static final String PORT_OPENED = "PORT_OPENED";
    public static final String LISTENING_STARTED = "LISTENING_STARTED";
    public static final String LISTENING_STOPPED = "LISTENING_STOPPED";
    public static final String PORT_CLOSED = "PORT_CLOSED";
    public static final String DEVICE_MAPPINGS = "DEVICE_MAPPINGS";
    public static final String NO_ROUTE = "NO_ROUTE";
}
