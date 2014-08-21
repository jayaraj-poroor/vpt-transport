package org.shelloid.common;

/*
 * 
 */


import java.sql.*;
import java.util.*;

/* @author Harikrishnan */
public class Database
{
    public static ArrayList<HashMap<String, Object>> getResult(Connection conn, String query, Object[] values) throws SQLException
    {
        /* TODO: implement database worker thread */
        ArrayList<HashMap<String, Object>> list = new ArrayList<>();
        PreparedStatement st = conn.prepareStatement(query);
        for (int i = 0; i < values.length; i++)
        {
            st.setObject(i + 1, values[i]);
        }
        ResultSet rs = st.executeQuery();
        ResultSetMetaData metaData = rs.getMetaData();
        while (rs.next())
        {
            HashMap<String, Object> map = new HashMap<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++)
            {
                map.put(metaData.getColumnName(i), rs.getObject(i));
            }
            list.add(map);
        }
        return list;
    }

    public static long doUpdate(Connection conn, String query, Object[] values) throws SQLException
    {
        long retVal;
        PreparedStatement st = conn.prepareStatement(query);
        for (int i = 0; i < values.length; i++)
        {
            st.setObject(i + 1, values[i]);
        }
        retVal = st.executeUpdate();
        ResultSet rs = st.getGeneratedKeys();
        if (rs.next())
        {
            retVal = rs.getLong(1);
        }
        return retVal;
    }
}
