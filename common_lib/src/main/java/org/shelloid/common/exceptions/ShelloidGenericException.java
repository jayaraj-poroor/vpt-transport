package org.shelloid.common.exceptions;

public class ShelloidGenericException extends Exception
{
    private static final long serialVersionUID = 1L;
    public Throwable currentException;

    public ShelloidGenericException(Throwable t)
    {
        super(t);
        currentException = t;
    }
}
