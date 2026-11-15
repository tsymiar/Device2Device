package javax.management;

public class OperationsException extends JMException {
    public OperationsException() {
        super();
    }

    public OperationsException(String message) {
        super(message);
    }

    public OperationsException(String message, Throwable cause) {
        super(message, cause);
    }

    public OperationsException(Throwable cause) {
        super(cause);
    }

    public OperationsException(Throwable cause, String message) {
        super(message, cause);
    }
}
