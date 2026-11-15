package javax.management;

public class RuntimeOperationException extends JMException {
    public RuntimeOperationException() {
        super();
    }

    public RuntimeOperationException(String message) {
        super(message);
    }

    public RuntimeOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    public RuntimeOperationException(Throwable cause) {
        super(cause);
    }

    public RuntimeOperationException(Throwable cause, String message) {
        super(message, cause);
    }
}
