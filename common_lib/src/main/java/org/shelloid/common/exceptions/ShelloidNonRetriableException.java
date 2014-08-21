package org.shelloid.common.exceptions;

/* @author Harikrishnan */
public class ShelloidNonRetriableException extends Exception
{

    private static final long serialVersionUID = 1L;

    public ShelloidNonRetriableException(String msg)
    {
        super(msg);
    }

    public ShelloidNonRetriableException(Throwable t)
    {
        super(t);
    }
}
