package org.shelloid.common.messages;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import org.shelloid.common.exceptions.ShelloidNonRetriableException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ShelloidMessage implements Serializable
{
    private static final long serialVersionUID = 1L;
    private Map<String, Object> outputMap;

    public ShelloidMessage()
    {
        outputMap = new LinkedHashMap<>();
    }

    public ShelloidMessage put(String key, String val)
    {
        if (outputMap == null){
            outputMap = new LinkedHashMap<>(); 
        }
        outputMap.put(key, val);
        return this;
    }
    
    public ShelloidMessage put(String key, byte[] val){
        outputMap.put(key, val);
        return this;
    }
    
    public ShelloidMessage put(String key, boolean val){
        outputMap.put(key, val);
        return this;
    }
    
    public ShelloidMessage put(String key, Map<String, Object> val)
    {
        outputMap.put(key, val);
        return this;
    }
    
    public ShelloidMessage put (String key, ShelloidMessage val){
        outputMap.put(key, val.outputMap);
        return this;
    }
    
    public ShelloidMessage put(String key, Object [] val)
    {
        outputMap.put(key, val);
        return this;
    }
    
    public ShelloidMessage put(String key, HashMap<?,?> val)
    {
        outputMap.put(key, val);
        return this;
    }

    public ShelloidMessage put(String key, int val)
    {
        outputMap.put(key, val + "");
        return this;
    }

    public ShelloidMessage put(String key, long val)
    {
        outputMap.put(key, val + "");
        return this;
    }

    public Object get(String key)
    {
        return outputMap.get(key);
    }
    
    public ArrayList<LinkedTreeMap> getArray(String key)
    {
        return (ArrayList<LinkedTreeMap>) outputMap.get(key);
    }
    
    public Map<String, Object> getMap()
    {
        return outputMap;
    }
    
    public void setMap(Map<String, Object> map)
    {
        outputMap = map;
    }

    public String getString(String key)
    {
        Object val = outputMap.get(key);
        return (val == null ? null : val.toString());
    }

    public long getLong(String key)
    {
        return Long.parseLong(getString(key));
    }

    public String getJson()
    {
        return new Gson().toJson(outputMap);
    }

    public static ShelloidMessage parse(String data) throws ShelloidNonRetriableException
    {
        try
        {
            ShelloidMessage msg = new ShelloidMessage();
            if (null != data && data.trim().length() > 0){
                msg.outputMap = new Gson().fromJson(data, LinkedHashMap.class);
            }
            return msg;
        }
        catch (Exception ex)
        {
            System.err.println("Parse Error for String " + data);
            throw new ShelloidNonRetriableException(ex);
        }
    }

    public void remove(String key)
    {
        outputMap.remove(key);
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    public byte[] getByteArray(String key) {
        return (byte[]) getObject(key);
    }
    
    public Object getObject(String key){
        return outputMap.get(key);
    }

    public boolean isEmpty() {
        return outputMap == null || outputMap.isEmpty();
    }
}
