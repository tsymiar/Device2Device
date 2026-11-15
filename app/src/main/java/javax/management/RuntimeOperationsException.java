package javax.management;

public class RuntimeOperationsException extends OperationsException {
    public RuntimeOperationsException() {
        super();
    }

    public RuntimeOperationsException(String message) {
        super(message);
    }

    public RuntimeOperationsException(String message, Throwable cause) {
        super(message, cause);
    }

    public RuntimeOperationsException(Throwable cause) {
        super(cause);
    }

    public RuntimeOperationsException(Throwable cause, String message) {
        super(message, cause);
    }
}
