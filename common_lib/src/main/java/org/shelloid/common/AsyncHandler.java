/*
 * 
 */

package org.shelloid.common;

/* @author Harikrishnan */
public interface AsyncHandler<T, K> {
    public void eventFired(T event, K... args);
}
