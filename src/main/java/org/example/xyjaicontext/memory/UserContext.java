package org.example.xyjaicontext.memory;

/**
 * 线程局部变量工具，用于在调用链中传递当前用户名
 */
public class UserContext {
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    public static void setUsername(String username) {
        CURRENT_USER.set(username);
    }

    public static String getUsername() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}