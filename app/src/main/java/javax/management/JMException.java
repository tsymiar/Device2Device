package javax.management;

/**
 * 占位桩：Android 运行时不包含 javax.management，而 Apache MINA SSHD 的
 * ExceptionUtils.peelException 在「某安全算法（如 curve25519 KeyAgreement）不可用」时会
 * 引用 javax.management.ReflectionException 等类型做 instanceof 判断，缺失会触发
 * NoClassDefFoundError 把整个进程带崩。这里提供最小可用桩类即可让类解析通过，
 * 使 isSupported() 能被正常捕获并退回 false（该算法被自动排除）。
 */
public class JMException extends Exception {
    public JMException() {
        super();
    }

    public JMException(String message) {
        super(message);
    }

    public JMException(String message, Throwable cause) {
        super(message, cause);
    }

    public JMException(Throwable cause) {
        super(cause);
    }
}
