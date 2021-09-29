/*Copyright 2021 Cognitive Medical Systems*/
import org.slf4j.Logger;



public class OutputProcessor {
    private boolean verbose = false;
    private boolean unmuted = true;
    boolean expectionsLogged = false;
    private Logger logger;

    public OutputProcessor()
    {

    }

    public OutputProcessor(Logger logger, boolean verbose, boolean unmuted)
    {
        this.logger = logger;
        this.verbose = verbose;
        this.unmuted = unmuted;
    }

    public boolean hasLoggedExceptions() {return expectionsLogged;};

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isUnmuted() {
        return unmuted;
    }

    public void setUnmuted(boolean unmutted) {
        this.unmuted = unmutted;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void println(String msg) {
        if (unmuted) {
            System.out.println(msg);
        } else {
            logger.debug(msg);
        }
    }

    public void vprintln(String msg) {
        if (verbose) {
            if (unmuted) {
                System.out.println(msg);
            } else {
                logger.debug(msg);
            }
        }
    }

    public void printException(String msg) {
        expectionsLogged = true;
        if (unmuted) {
            System.out.println(msg);
        } else {
            System.err.println(msg);
        }
    }

    public void printException(Exception exp) {
        expectionsLogged = true;
        if (unmuted) {
            exp.printStackTrace(System.out);
        } else {
            exp.printStackTrace(System.err);

        }
    }

    public void setLogger(Logger logger) {
        this.logger = logger;
    }
}
