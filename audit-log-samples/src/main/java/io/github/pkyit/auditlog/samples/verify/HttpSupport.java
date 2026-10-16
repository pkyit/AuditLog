package io.github.pkyit.auditlog.samples.verify;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 极简 HTTP 客户端：只依赖 JDK。
 *
 * <p>验收必须走真实 HTTP 链路，因此不能依赖 WebClient/RestTemplate：这两者都会引入
 * 额外的自动配置，与验收要观察的框架行为相互干扰。
 *
 * <p>连接超时 10 秒，读取超时 30 秒；4xx/5xx 不抛异常，其响应体照常返回，以便断言被拒绝
 * 请求的审计语义。
 */
public final class HttpSupport {

	private HttpSupport() {
	}

	/**
	 * 一次 HTTP 调用的结果。
	 *
	 * @param status HTTP 状态码，不做成功/失败的区分
	 * @param body 响应体按 UTF-8 解码后的文本；无响应体时为 {@code ""}，绝不为 {@code null}
	 */
	public record Response(int status, String body) {
	}

	/**
	 * 发起 GET 请求。
	 *
	 * @param url 完整请求地址
	 * @param headers 请求头，按 {@code 名称, 值} 成对排列；落单的末尾元素被忽略
	 * @return 响应；4xx/5xx 同样作为正常返回值返回，不抛异常
	 * @throws IOException 连接失败或读写超时
	 */
	public static Response get(String url, String... headers) throws IOException {
		return request("GET", url, null, headers);
	}

	/**
	 * 发起 POST 请求。
	 *
	 * @param url 完整请求地址
	 * @param body 请求体，以 {@code application/json;charset=UTF-8} 发送；为 {@code null} 时不带请求体
	 * @param headers 请求头，按 {@code 名称, 值} 成对排列；落单的末尾元素被忽略
	 * @return 响应；4xx/5xx 同样作为正常返回值返回，不抛异常
	 * @throws IOException 连接失败或读写超时
	 */
	public static Response post(String url, String body, String... headers) throws IOException {
		return request("POST", url, body, headers);
	}

	/**
	 * 发起 DELETE 请求。
	 *
	 * @param url 完整请求地址
	 * @param headers 请求头，按 {@code 名称, 值} 成对排列；落单的末尾元素被忽略
	 * @return 响应；4xx/5xx 同样作为正常返回值返回，不抛异常
	 * @throws IOException 连接失败或读写超时
	 */
	public static Response delete(String url, String... headers) throws IOException {
		return request("DELETE", url, null, headers);
	}

	/**
	 * 所有请求方法的统一出口，负责设置超时与请求头、发送请求体、读取响应以及断开连接。
	 *
	 * @param method HTTP 方法名，必须是 {@link HttpURLConnection} 接受的合法方法
	 * @param url 完整请求地址
	 * @param body 请求体；为 {@code null} 时省略输出流与 Content-Type
	 * @param headers 请求头，按 {@code 名称, 值} 成对排列
	 * @return 响应；无响应体时为 {@code ""}，绝不为 {@code null}
	 * @throws IOException 连接失败、读写超时或 HTTP 方法非法
	 */
	private static Response request(String method, String url, String body, String... headers) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
		try {
			connection.setRequestMethod(method);
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(30_000);
			// 不跟随重定向：3xx 的响应体本身就是验收要断言的审计线索
			connection.setInstanceFollowRedirects(false);
			for (int index = 0; index + 1 < headers.length; index += 2) {
				connection.setRequestProperty(headers[index], headers[index + 1]);
			}
			if (body != null) {
				connection.setDoOutput(true);
				byte[] payload = body.getBytes(StandardCharsets.UTF_8);
				connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
				connection.setFixedLengthStreamingMode(payload.length);
				try (java.io.OutputStream output = connection.getOutputStream()) {
					output.write(payload);
				}
			}
			int status = connection.getResponseCode();
			java.io.InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
			String responseBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			return new Response(status, responseBody);
		}
		finally {
			connection.disconnect();
		}
	}
}
