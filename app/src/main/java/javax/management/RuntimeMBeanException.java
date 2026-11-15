package javax.management;

public class RuntimeMBeanException extends JMException {
    public RuntimeMBeanException() {
        super();
    }

    public RuntimeMBeanException(String message) {
        super(message);
    }

    public RuntimeMBeanException(String message, Throwable cause) {
        super(message, cause);
    }

    public RuntimeMBeanException(Throwable cause) {
        super(cause);
    }

    public RuntimeMBeanException(Throwable cause, String message) {
        super(message, cause);
    }
}
