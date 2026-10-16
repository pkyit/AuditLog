package io.github.pkyit.auditlog.core.model;

/**
 * 异常摘要。
 *
 * <p>只记录异常消息会丢失类型与根因，失败将无法归因，因此摘要必须保留类型、消息与根因链。
 * 堆栈按配置可选，默认关闭：完整堆栈会泄漏内部实现细节并显著放大记录体积。
 */
public record ErrorInfo(String type, String message, String rootCauseType, String rootCauseMessage, String stackTrace) {

	/**
	 * 由异常构造摘要。
	 *
	 * @param error             源异常
	 * @param includeStackTrace 是否携带堆栈，携带时按 8192 字符截断
	 * @param maxMessageLength  消息与根因消息的长度上限，不超过 0 时表示不截断
	 * @return 异常摘要；{@code error} 为 {@code null} 时为 {@code null}
	 */
	public static ErrorInfo of(Throwable error, boolean includeStackTrace, int maxMessageLength) {
		if (error == null) {
			return null;
		}
		Throwable root = rootCause(error);
		return new ErrorInfo(
			error.getClass().getName(),
			truncate(error.getMessage(), maxMessageLength),
			root.getClass().getName(),
			truncate(root.getMessage(), maxMessageLength),
			includeStackTrace ? truncate(stackTraceOf(error), 8192) : null);
	}

	private static Throwable rootCause(Throwable error) {
		Throwable current = error;
		int guard = 0;
		// cause 链可能成环（互相引用），必须设上界，否则遍历不会终止。
		while (current.getCause() != null && current.getCause() != current && guard++ < 32) {
			current = current.getCause();
		}
		return current;
	}

	private static String stackTraceOf(Throwable error) {
		java.io.StringWriter writer = new java.io.StringWriter();
		error.printStackTrace(new java.io.PrintWriter(writer));
		return writer.toString();
	}

	private static String truncate(String value, int maxLength) {
		if (value == null || maxLength <= 0 || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength) + "...(truncated)";
	}
}
