/*
 * 
 */

package org.shelloid.common.enums;

/* @author Harikrishnan */
public enum HttpContentTypes {
    TEXT_HTML("text/html; charset=UTF-8"),
    TEXT_JSON("text/json; charset=UTF-8");

    private final String text;

    private HttpContentTypes(final String text) {
        this.text = text;
    }
    
    @Override
    public String toString() {
        return text;
    }
}