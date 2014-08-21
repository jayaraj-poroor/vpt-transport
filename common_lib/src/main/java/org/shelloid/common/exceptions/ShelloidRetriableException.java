
package org.shelloid.common.exceptions;

/*  @author Harikrishnan */
public class ShelloidRetriableException extends Exception
{
    private static final long serialVersionUID = 1L;

    public ShelloidRetriableException(Exception ex)
    {
        super(ex);
    }
}
