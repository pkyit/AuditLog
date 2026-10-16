package io.github.pkyit.auditlog.tools;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;

/**
 * 离线单元测试启动器。
 *
 * <p><b>为什么需要它</b>：本仓库的开发环境离线，且本地 Maven 仓库中
 * {@code maven-surefire-plugin} 的 provider 依赖（{@code surefire-junit-platform} 等）
 * 只有空目录、没有 jar，因此 {@code mvn test} 无法执行。
 * 但 JUnit Platform 自身的 {@code launcher} 是齐备的，
 * 所以这里用平台启动器直接跑测试 —— 与 surefire 执行的是同一套 JUnit 5 引擎。
 *
 * <p>用法（由 {@code tools/run-tests.ps1} 调用）：
 * <pre>
 * java -cp "&lt;测试 classpath&gt;" io.github.pkyit.auditlog.tools.JUnitConsoleRunner &lt;扫描根目录&gt;
 * </pre>
 *
 * <p>退出码：0 表示全部通过，1 表示存在失败或异常。
 */
public final class JUnitConsoleRunner {

	private JUnitConsoleRunner() {
	}

	public static void main(String[] args) {
		if (args.length == 0) {
			System.err.println("用法: JUnitConsoleRunner <测试类扫描根目录> [全限定类名#方法名]");
			System.exit(2);
			return;
		}
		java.nio.file.Path root = java.nio.file.Path.of(args[0]);
		if (!java.nio.file.Files.isDirectory(root)) {
			System.err.println("扫描根目录不存在: " + root.toAbsolutePath());
			System.exit(2);
			return;
		}

		LauncherDiscoveryRequest request;
		if (args.length >= 2 && args[1].contains("#")) {
			// 精确到方法：用于隔离排查单个用例的稳定性。
			// 注意必须加载 Class 后再按方法名选择，否则 JUnit 无法解析 @TempDir 这类
			// 由参数解析器提供的参数，会在发现阶段直接失败。
			String[] parts = args[1].split("#", 2);
			System.out.println("=== 仅运行: " + parts[0] + "#" + parts[1] + " ===");
			try {
				Class<?> testClass = Class.forName(parts[0]);
				request = LauncherDiscoveryRequestBuilder.request()
					.selectors(DiscoverySelectors.selectMethod(testClass, parts[1]))
					.build();
			}
			catch (ClassNotFoundException error) {
				System.err.println("测试类不存在: " + parts[0]);
				System.exit(2);
				return;
			}
		}
		else if (args.length >= 2 && !args[1].isBlank()) {
			System.out.println("=== 仅运行类: " + args[1] + " ===");
			request = LauncherDiscoveryRequestBuilder.request()
				.selectors(DiscoverySelectors.selectClass(args[1]))
				.build();
		}
		else {
			System.out.println("=== 扫描测试类目录: " + root.toAbsolutePath() + " ===");
			request = LauncherDiscoveryRequestBuilder.request()
				.selectors(DiscoverySelectors.selectClasspathRoots(java.util.Set.of(root)))
				.build();
		}

		Launcher launcher = LauncherFactory.create();
		SummaryGeneratingListener listener = new SummaryGeneratingListener();
		launcher.execute(request, listener);

		TestExecutionSummary summary = listener.getSummary();
		PrintWriter writer = new PrintWriter(System.out, true);

		summary.printTo(writer);

		long failed = summary.getTotalFailureCount();
		long succeeded = summary.getTestsSucceededCount();
		long found = summary.getTestsFoundCount();

		System.out.println("------------------------------------------------------------");
		System.out.println(" 发现用例: " + found + "，通过: " + succeeded + "，失败/异常: " + failed);
		System.out.println("------------------------------------------------------------");

		if (failed > 0) {
			summary.printFailuresTo(writer, 20);
			writer.flush();
			System.exit(1);
		}
		if (found == 0) {
			System.err.println("未发现任何测试用例 —— 请检查扫描目录是否正确");
			System.exit(1);
		}
		writer.flush();
		System.exit(0);
	}
}
