package javax.management;

public class MBeanException extends JMException {
    public MBeanException() {
        super();
    }

    public MBeanException(String message) {
        super(message);
    }

    public MBeanException(String message, Throwable cause) {
        super(message, cause);
    }

    public MBeanException(Throwable cause) {
        super(cause);
    }

    public MBeanException(Throwable cause, String message) {
        super(message, cause);
    }
}
